# 运行时配置管理与灰度发布（Spring Boot）

配置变更**无需重启**即可原子生效；支持按主体（用户/设备/租户）做百分比灰度放量；
配置来源支持优先级与回退；非法变更被**整体拒绝**并返回可区分的原因码；
新旧结构不兼容、回滚、连续多次变更与并发读写下，判定结论稳定、可复现、可解释。

## 1. 总体结构

```
config/
├── model/        值类型、配置条目、灰度规则、版本快照、判定结论、拒绝原因、受检异常
├── source/       配置来源抽象（Map / Properties / 文件引导）
├── rollout/      稳定分桶散列（FNV-1a）与灰度门
├── validation/   值强校验、来源优先级合并、版本兼容性校验
├── EvaluationView           请求级判定视图（绑定一个不可变版本）
├── RuntimeConfigService     核心服务：bootstrap / publish / rollback / evaluate
└── web/ConfigAdminController  HTTP 管理与判定接口
```

核心保证：

| 需求 | 实现方式 |
| --- | --- |
| 热更新无中间态 | 每个版本是**不可变快照**，发布仅原子切换一个引用 |
| 同一请求不串版本 | 请求开始时 `openView()` 绑定快照，视图内所有判定固定在该版本 |
| 灰度结论可复现 | `FNV-1a(key + salt + subject) mod 100` 的纯函数分桶 |
| 不同比例人群一致 | 放量区间恒为 `bucket < percentage`，扩量只增不减（单调） |
| 非法变更不部分生效 | 先整体校验，通过后才原子提交；失败旧版本原样保留 |
| 并发不脏读 | 不可变对象 + 原子引用读、写操作串行化 + 乐观版本号 |

## 2. 配置来源与优先级

- 每个来源有 `priority`（整数，**越大越优先**）与唯一 `name`。
- 合并规则：
  1. 同一键在多个来源出现：**高优先级来源的值胜出**，低优先级自动回退补缺；
  2. 同一键在不同来源中的**类型不同**：拒绝（`SOURCE_TYPE_CONFLICT`），不做隐式转换；
  3. 同一优先级出现**重名来源**：拒绝（`DUPLICATE_SOURCE`）；
  4. 必填键在所有来源缺失：拒绝（`DEFAULT_VALUE_MISSING`）。
- 启动引导（`ConfigBootstrap`）默认依次尝试
  `file:./config/runtime-config.properties`（高）与
  `classpath:runtime-config.properties`（低），高优先级文件缺失则回退到 classpath；
  可用 `config.bootstrap.locations` 覆盖。**文件存在但内容非法时启动直接失败**（fail-fast）。
- Properties 值格式为 `TYPE:value`，类型取值 `STRING / INTEGER / BOOLEAN / DECIMAL`，
  省略类型默认 `STRING`。例如：

  ```properties
  feature.theme=STRING:light
  feature.timeoutMs=INTEGER:1000
  feature.newUi=BOOLEAN:false
  feature.sampleRatio=DECIMAL:0.05
  ```

## 3. 灰度规则

- 每个条目带 `percentage ∈ [0,100]` 与可选 `salt`：
  - `100`：全量；`0`：不放量（全部回退到父版本）。
  - 主体桶号：`bucket = FNV1a_64(key + " " + salt + " " + subject) mod 100`，
    同一三元组永远得到同一桶号，与时间、节点无关，可跨实例复现。
  - 命中条件：`bucket < percentage`。
- **一致性（嵌套放量）**：10% 命中人群必然包含在 30%/50%/100% 命中人群中，
  放量比例上调不会把已命中的主体“抖出”放量集合。
- **确定性回退**：灰度未命中时沿版本父子链回退到该键上一版本的值，
  而不是静默使用空值或默认值。根版本必须全量（100%），保证回退链有终点。
- 不同配置项使用不同 `salt` 可避免灰度人群完全重叠。

## 4. 兼容范围与拒绝原因

首次 `bootstrap` 建立配置**模式（schema，键集合 + 类型）**，之后：

- ✅ 允许：值变更、灰度比例调整、全量/灰度互相切换、回滚到任意历史版本；
- ❌ 拒绝：改键类型（`INCOMPATIBLE_TYPE_CHANGE`）、删键（`INCOMPATIBLE_KEY_REMOVED`）、
  增加无基线的新键（`ROLLOUT_WITHOUT_BASELINE`）；
- ❌ 拒绝：空键（`INVALID_KEY`）、空值（`MISSING_VALUE`）、值无法解析为声明类型
  （`TYPE_MISMATCH`）、百分比越界（`INVALID_PERCENTAGE`）；
- ❌ 拒绝：基于过期版本号发布（`VERSION_CONFLICT`）、回滚目标不存在
  （`UNKNOWN_TARGET_VERSION`）、判定未知键（`UNKNOWN_KEY`）。

回滚语义：以目标历史版本的**条目内容**生成一个新版本，父链沿用目标版本的父链，
因此回滚后任意主体的判定结论与该历史版本当时**完全一致**（版本号是新的，内容是旧的）。

## 5. HTTP 接口

| 方法与路径 | 说明 |
| --- | --- |
| `GET /config/evaluate/{key}?subject=u123` | 判定；返回版本、桶号、是否命中、值、来源、判定依据 |
| `GET /config/current` | 当前版本完整内容 |
| `GET /config/history` | 历史版本链（审计/回滚） |
| `POST /config/publish` | 灰度/全量发布，整体校验、原子生效 |
| `POST /config/rollback` | 按历史版本号回滚 |

发布示例（携带乐观锁版本号）：

```bash
curl -XPOST localhost:8080/config/publish -H 'Content-Type: application/json' -d '{
  "expectedVersion": "v1",
  "note": "dark theme 10% canary",
  "items": [
    {"key": "feature.theme", "type": "STRING", "value": "dark",
     "percentage": 10, "salt": "theme-2026q4"},
    {"key": "feature.timeoutMs", "type": "INTEGER", "value": 1000, "percentage": 100}
  ]
}'
```

判定响应：

```json
{
  "key": "feature.theme", "subject": "u123", "value": "dark", "type": "STRING",
  "version": "v2", "bucket": 7, "hitRollout": true, "percentage": 10,
  "source": "http-publish",
  "reason": "命中版本 v2 的放量区间: bucket=7 < percentage=10（灰度）"
}
```

非法请求统一返回 `422`：`{"rejected": true, "reason": "<原因码>", "message": "..."}`，
旧版本不受任何影响。

## 6. 代码内使用（请求内多键判定）

```java
try (EvaluationView view = runtimeConfigService.openView()) {   // 请求开始，固定版本
    String theme = (String) view.evaluate("feature.theme", userId).value();
    long timeout = (Long) view.evaluate("feature.timeoutMs", userId).value();
    // 即使此刻另一个线程发布了新版本，本视图内两键仍来自同一版本
}
```

## 7. 验证方法

```bash
mvn test
```

覆盖 19 个用例（5 个测试类）：

- `RolloutHasherTest`：桶号稳定性、取值范围、2 万样本分布均匀性、加盐隔离；
- `ConfigSourcePriorityTest`：优先级覆盖与低优先级补缺、缺省值缺失、
  来源类型冲突、来源重名、非法值原因区分、三类不兼容变更；
- `RuntimeConfigServiceTest`：
  - 热更新原子生效且可复现；
  - 10%→50%→100% 灰度边界单调性与全量一致性；
  - 非法发布整体拒绝、旧版本不被部分生效、乐观锁冲突；
  - 回滚后判定与历史版本逐主体一致；
  - 40 次连续发布 × 24000 次请求级判定并发压测，无中间态/脏读；
  - 未知键、空主体拒绝。
- `ConfigAdminIntegrationTest`：HTTP 全链路发布、灰度分流、5 类拒绝原因码。

测试日志中每条判定均打印**配置版本、命中人群（subject/bucket）、放量比例与判定依据**，
形如：

```
配置判定 version=v2 key=feature.theme subject=user-3 hitRollout=true bucket=3
  percentage=10 value=dark source=test reason=命中版本 v2 的放量区间: bucket=3 < percentage=10（灰度）
```
