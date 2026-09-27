# 运行时配置管理与灰度发布（Spring Boot）

配置变更通过 HTTP 管理接口发布，**无需重启**即可生效。核心保证：

- **无中间态**：当前生效配置是一个不可变快照（`volatile` 引用）。更新时先在局部构建完整新快照并做全部校验，通过后一次性原子替换引用。任何一次请求从头到尾只读同一个版本，不会读到"改了一半"的配置。
- **判定可复现**：灰度分桶只由 `SHA-256(key + "|" + subject)` 决定，不依赖时间/随机数/节点，任何实例、任何时刻结果一致。
- **拒绝可区分**：非法变更整体拒绝并返回原因码，当前版本与旧值继续生效，绝不静默使用旧值或部分生效。
- **回退即发布**：回退是取出历史快照中某来源的内容、重新完整校验后作为**新版本**发布（保留 `rollbackOf` 溯源），连续变更/回滚结论稳定可解释。

## 1. 模块结构

| 包 | 职责 |
|---|---|
| `domain` | 不可变模型：`ConfigSnapshot`、`ConfigEntry`、`SourceEntry`、`DecisionResult`、schema 描述 |
| `registry` | 配置项 schema 注册表（键、类型、必填、缺省值、取值范围、结构版本号） |
| `gray` | `BucketHasher`：一致性哈希分桶（0-99） |
| `service` | `ConfigValidator`（来源级/快照级校验）、`ConfigManager`（快照发布、查询、回退、历史） |
| `web` | 查询接口、管理接口、统一异常翻译（原因码 + HTTP 状态） |
| `config` | 模块引导属性（来源优先级、历史保留数量） |

## 2. 配置来源优先级与回退语义

`application.properties` 中按索引配置来源，**索引越小优先级越高**：

```properties
runtime-config.sources[0]=database   # 最高优先级
runtime-config.sources[1]=file
runtime-config.sources[2]=default    # 最低优先级
runtime-config.history-size=20
```

查询某个键时按优先级遍历每个来源上的条目，第一条**命中该主体灰度人群**的条目生效；
全部来源都未覆盖该主体时，回退到 schema 注册的**缺省值**（`source=SCHEMA_DEFAULT`）；
必填键既无来源值也无缺省值时，查询返回 `MISSING_REQUIRED_DEFAULT`（404）。

**冲突策略**：多个来源对同一键给出 `rolloutPercent=100`（全量）但取值不同属于硬冲突，
提交时以 `SOURCE_CONFLICT` 拒绝；高优先级来源只是灰度（&lt;100%）时，未进灰度的主体
会自然回退到低优先级来源的全量旧值，这是有意设计的**灰度放量/回退链**语义，不是冲突。

## 3. 灰度规则

- 桶号：`bucket = SHA-256(key + "|" + subject) mod 100`，固定区间 `[0,99]`。
- 命中：`bucket < rolloutPercent`。`100` = 全量（FULL），`0` = 不对任何人生效。
- **单调一致放量**：桶号与比例无关，因此命中集合严格满足
  `10% 人群 ⊆ 50% 人群 ⊆ 100% 人群`。比例从 10→50→100 调整时，同一主体不会"先命中后掉出"。
- 不同配置键使用不同哈希输入，灰度人群相互独立。
- 判定结果返回完整依据：`version / source / rolloutPercent / bucket / cohort(GRAY|FULL) / basis`。

## 4. 兼容范围与降级

- 当前结构版本 `SCHEMA_VERSION = 1`。提交内容中的键必须在 schema 注册，
  否则拒绝为 `UNKNOWN_KEY`（新老结构不兼容的新键不能直接进入运行态）。
- 值类型必须匹配（BOOLEAN / INTEGER / STRING），整数受 min/max 约束，
  分别拒绝为 `INVALID_VALUE_TYPE` / `VALUE_OUT_OF_RANGE`。
- 请求可带 `schemaVersion`，与服务端不一致时拒绝为 `SCHEMA_VERSION_INCOMPATIBLE`。
- 回退同样执行快照级校验：回退结果若与当前其他来源冲突、或导致必填键无值，
  回退会被拒绝（`SOURCE_CONFLICT` / `MISSING_REQUIRED_DEFAULT`），不会产生脏状态。
- 并发提交：写操作串行化 + 乐观版本号（`expectedVersion`），过期提交拒绝为
  `VERSION_CONFLICT`（409），不会互相覆盖。

### 拒绝原因码一览

| reason | 触发场景 | HTTP |
|---|---|---|
| `ILLEGAL_PAYLOAD` | JSON 不可解析 / key、value、entries 缺失 | 400/422 |
| `UNKNOWN_SOURCE` | 来源不在优先级列表中 | 404 |
| `UNKNOWN_KEY` | 键未在 schema 注册 | 404 |
| `DUPLICATE_KEY_IN_SOURCE` | 同一来源载荷内键重复 | 422 |
| `INVALID_ROLLOUT_PERCENT` | 灰度比例不在 0-100 或缺失 | 422 |
| `INVALID_VALUE_TYPE` | 值类型与 schema 不符 | 422 |
| `VALUE_OUT_OF_RANGE` | 整数超出 min/max | 422 |
| `SOURCE_CONFLICT` | 多个全量来源对同键给出不同值 | 422 |
| `MISSING_REQUIRED_DEFAULT` | 必填键无缺省值且无来源提供 | 422（提交）/ 404（查询） |
| `SCHEMA_VERSION_INCOMPATIBLE` | 结构版本号不一致 | 422 |
| `VERSION_CONFLICT` | 乐观版本号过期 | 409 |
| `VERSION_NOT_FOUND` | 回退目标版本不在历史中 | 404 |

## 5. HTTP 接口

### 查询

```bash
curl 'http://localhost:8080/api/config?key=feature.recommend.strategy&subject=user-38'
# { found:true, value:"fresh", version:2, source:"database",
#   rolloutPercent:50, bucket:49, cohortHit:true,
#   basis:"source=database, rollout=50%, bucket=49, cohort=GRAY", reason:null }

curl 'http://localhost:8080/api/config/snapshot'   # 当前不可变快照视图
```

### 热更新（全量替换某来源内容）

```bash
curl -X POST http://localhost:8080/api/admin/config/update \
  -H 'Content-Type: application/json' \
  -d '{"source":"database","expectedVersion":2,"entries":[
        {"key":"feature.recommend.strategy","value":"fresh","rolloutPercent":50},
        {"key":"feature.search.page-size","value":"20","rolloutPercent":100}]}'
```

`expectedVersion` 可选；提供时必须等于当前版本，否则 409。

### 按来源回退

```bash
curl -X POST http://localhost:8080/api/admin/config/rollback \
  -H 'Content-Type: application/json' \
  -d '{"source":"database","targetVersion":2}'
```

## 6. 验证方法

```bash
mvn test
```

共 25 个用例（`src/test/java/.../tests`）：

- `BucketHasherTest`：分桶稳定可复现、放量单调包含、0/49/50/99 边界、均匀性；
- `ConfigValidatorTest`：9 类非法输入各自的拒绝原因码、合法载荷通过；
- `ConfigManagerTest`：热更新生效、灰度边界与可复现、放量 10→50→100 同主体一致、
  来源优先级回退、非法提交后旧值保持、未知来源、乐观锁冲突、回退发布、回退版本不存在、
  300 次并发更新期间读者无脏读/无中间态；
- `ConfigApiIntegrationTest`：端到端更新→灰度查询（命中/回退缺省）→4 类非法请求各自的
  原因码→旧版本保持→回退发布→非法回退被拒绝。

测试日志（同时输出到控制台与 `target/surefire-reports/*-output.txt`）会打印**配置版本、
命中人群（GRAY/FULL）、桶号、命中来源与判定依据**，例如：

```
配置命中: version=2, key=feature.recommend.strategy, subject=user-38,
  hitCohort=GRAY, source=database, bucket=49, rollout=50%,
  basis=source=database, rollout=50%, bucket=49, cohort=GRAY
```
