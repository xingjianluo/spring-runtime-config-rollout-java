package com.github.xingjianluo.springruntimeconfigrolloutjava.service;

import com.github.xingjianluo.springruntimeconfigrolloutjava.config.RuntimeConfigProperties;
import com.github.xingjianluo.springruntimeconfigrolloutjava.domain.ConfigEntry;
import com.github.xingjianluo.springruntimeconfigrolloutjava.domain.ConfigKeyDescriptor;
import com.github.xingjianluo.springruntimeconfigrolloutjava.domain.ConfigSnapshot;
import com.github.xingjianluo.springruntimeconfigrolloutjava.domain.DecisionResult;
import com.github.xingjianluo.springruntimeconfigrolloutjava.domain.SourceEntry;
import com.github.xingjianluo.springruntimeconfigrolloutjava.exception.ConfigNotFoundException;
import com.github.xingjianluo.springruntimeconfigrolloutjava.exception.ConfigRejectedException;
import com.github.xingjianluo.springruntimeconfigrolloutjava.exception.RejectionReason;
import com.github.xingjianluo.springruntimeconfigrolloutjava.gray.BucketHasher;
import com.github.xingjianluo.springruntimeconfigrolloutjava.registry.ConfigSchemaRegistry;
import com.github.xingjianluo.springruntimeconfigrolloutjava.web.dto.EntryInput;
import com.github.xingjianluo.springruntimeconfigrolloutjava.web.dto.SnapshotView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 配置管理核心。
 *
 * <p>一致性模型：
 * <ul>
 *   <li>当前生效配置是一个不可变 {@link ConfigSnapshot}，由 {@code volatile} 引用持有；
 *       更新时先在局部构建完整新快照并做全部校验，通过后一次性原子替换引用，
 *       因此任何请求从头到尾只会读到同一个版本，绝不会读到中间态；</li>
 *   <li>写方法 synchronized 串行化，配合 expectedVersion 乐观版本号防止并发覆盖；</li>
 *   <li>历史快照不可变且仅追加，回退是“取出历史内容并作为新版本发布”，
 *       连续多次变更/回退结论稳定、可解释；</li>
 *   <li>来源按配置优先级排序（索引小者优先），查询时顺序回退。</li>
 * </ul>
 */
@Service
public class ConfigManager {

    private static final Logger log = LoggerFactory.getLogger(ConfigManager.class);

    private final RuntimeConfigProperties properties;
    private final ConfigValidator validator;
    private final ConfigSchemaRegistry registry;
    private final BucketHasher hasher;
    private final List<String> sourceOrder;

    private volatile ConfigSnapshot current;
    private final Deque<ConfigSnapshot> history = new ArrayDeque<>();

    public ConfigManager(RuntimeConfigProperties properties, ConfigValidator validator,
                         ConfigSchemaRegistry registry, BucketHasher hasher) {
        this.properties = properties;
        this.validator = validator;
        this.registry = registry;
        this.hasher = hasher;
        this.sourceOrder = List.copyOf(properties.getSources());
        this.current = buildSnapshot(Map.of(), 1L, null);
    }

    /** 全量替换某来源内容并原子发布新版本。expectedVersion 非空时做乐观并发校验。 */
    public synchronized ConfigSnapshot updateSource(String source, List<EntryInput> entries,
                                                    Long expectedVersion) {
        if (source == null || source.isBlank() || !sourceOrder.contains(source)) {
            throw new ConfigRejectedException(RejectionReason.UNKNOWN_SOURCE,
                    "来源[" + source + "]未注册，合法来源(优先级从高到低): " + sourceOrder);
        }
        if (entries == null) {
            throw new ConfigRejectedException(RejectionReason.ILLEGAL_PAYLOAD,
                    "entries 不能为空");
        }
        if (expectedVersion != null && expectedVersion != current.version()) {
            throw new ConfigRejectedException(RejectionReason.VERSION_CONFLICT,
                    "期望基线版本 " + expectedVersion + "，当前版本已是 " + current.version());
        }
        validator.validateSourcePayload(source, entries);

        // 在局部副本上构建新的全量数据，当前快照不受影响，校验失败时旧值继续生效。
        Map<String, Map<String, ConfigEntry>> nextData = deepCopy(current.sourceData());
        Map<String, ConfigEntry> replaced = new LinkedHashMap<>();
        for (EntryInput input : entries) {
            replaced.put(input.key(), new ConfigEntry(input.value(), input.rolloutPercent()));
        }
        nextData.put(source, Map.copyOf(replaced));

        validator.validateSnapshot(registry.getSchemaVersion(), nextData);

        long newVersion = current.version() + 1;
        ConfigSnapshot next = buildSnapshot(nextData, newVersion, null);
        publish(next);
        log.info("配置热更新已发布: source={}, version={}, keys={}", source, newVersion,
                replaced.keySet());
        return next;
    }

    /** 把某来源回退到历史版本中该来源的内容，作为新版本发布。 */
    public synchronized ConfigSnapshot rollbackSource(String source, long targetVersion) {
        if (source == null || source.isBlank() || !sourceOrder.contains(source)) {
            throw new ConfigRejectedException(RejectionReason.UNKNOWN_SOURCE,
                    "来源[" + source + "]未注册");
        }
        ConfigSnapshot target = null;
        if (current.version() == targetVersion) {
            target = current;
        } else {
            for (ConfigSnapshot snapshot : history) {
                if (snapshot.version() == targetVersion) {
                    target = snapshot;
                    break;
                }
            }
        }
        if (target == null) {
            throw new ConfigRejectedException(RejectionReason.VERSION_NOT_FOUND,
                    "历史中不存在版本 " + targetVersion);
        }
        Map<String, ConfigEntry> oldSource = target.sourceData().getOrDefault(source, Map.of());

        // 回退仍然是一次完整校验的全新发布，防止回退内容与当前其他来源冲突。
        Map<String, Map<String, ConfigEntry>> nextData = deepCopy(current.sourceData());
        nextData.put(source, oldSource);
        validator.validateSnapshot(registry.getSchemaVersion(), nextData);

        long newVersion = current.version() + 1;
        ConfigSnapshot next = buildSnapshot(nextData, newVersion, targetVersion);
        publish(next);
        log.info("配置回退已发布: source={}, targetVersion={}, newVersion={}",
                source, targetVersion, newVersion);
        return next;
    }

    private void publish(ConfigSnapshot next) {
        history.push(current);
        while (history.size() > properties.getHistorySize()) {
            history.removeLast();
        }
        current = next; // 唯一的写发布点，对读者原子可见
    }

    /** 按主体做一次带灰度判定的配置查询。整个方法只读取一次快照引用，无中间态。 */
    public DecisionResult resolve(String key, String subject) {
        ConfigSnapshot snapshot = this.current;
        ConfigKeyDescriptor descriptor = registry.find(key)
                .orElseThrow(() -> new ConfigNotFoundException(RejectionReason.UNKNOWN_KEY,
                        "键[" + key + "]未在 schema 中注册"));

        List<SourceEntry> chain = snapshot.merged().getOrDefault(key, List.of());
        for (SourceEntry candidate : chain) {
            ConfigEntry entry = candidate.entry();
            int bucket = hasher.bucket(key, subject);
            boolean hit = hasher.hit(bucket, entry.rolloutPercent());
            if (hit) {
                String basis = "source=" + candidate.source()
                        + ", rollout=" + entry.rolloutPercent() + "%"
                        + ", bucket=" + bucket
                        + ", cohort=" + (entry.rolloutPercent() == 100 ? "FULL" : "GRAY");
                DecisionResult result = new DecisionResult(true, key, subject, entry.value(),
                        snapshot.version(), snapshot.schemaVersion(), candidate.source(),
                        entry.rolloutPercent(), bucket, true, basis, null);
                log.info("配置命中: version={}, key={}, subject={}, hitCohort={}, source={}, "
                                + "bucket={}, rollout={}%, basis={}",
                        snapshot.version(), key, subject,
                        entry.rolloutPercent() == 100 ? "FULL" : "GRAY",
                        candidate.source(), bucket, entry.rolloutPercent(), basis);
                return result;
            }
        }

        // 所有来源都未覆盖该主体，回退到 schema 缺省值。
        if (descriptor.hasDefault()) {
            String basis = "source=SCHEMA_DEFAULT, rollout=100%, cohort=FULL";
            DecisionResult result = new DecisionResult(true, key, subject,
                    descriptor.defaultValue(), snapshot.version(), snapshot.schemaVersion(),
                    "SCHEMA_DEFAULT", 100, null, true, basis, null);
            log.info("配置命中缺省值: version={}, key={}, subject={}, basis={}",
                    snapshot.version(), key, subject, basis);
            return result;
        }

        String reasonDetail = "所有来源均未覆盖该主体且键[" + key + "]无缺省值";
        log.info("配置未命中: version={}, key={}, subject={}, reason={}",
                snapshot.version(), key, subject, reasonDetail);
        throw new ConfigNotFoundException(RejectionReason.MISSING_REQUIRED_DEFAULT, reasonDetail);
    }

    public ConfigSnapshot currentSnapshot() {
        return current;
    }

    public SnapshotView currentView() {
        ConfigSnapshot snapshot = this.current;
        Map<String, Map<String, String>> values = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, ConfigEntry>> source
                : snapshot.sourceData().entrySet()) {
            Map<String, String> entries = new LinkedHashMap<>();
            for (Map.Entry<String, ConfigEntry> e : source.getValue().entrySet()) {
                entries.put(e.getKey(), e.getValue().value()
                        + "(rollout=" + e.getValue().rolloutPercent() + "%)");
            }
            values.put(source.getKey(), Map.copyOf(entries));
        }
        return new SnapshotView(snapshot.version(), snapshot.schemaVersion(),
                snapshot.createdAt(), snapshot.rollbackOf(), Map.copyOf(values));
    }

    private Map<String, Map<String, ConfigEntry>> deepCopy(
            Map<String, Map<String, ConfigEntry>> source) {
        Map<String, Map<String, ConfigEntry>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, ConfigEntry>> e : source.entrySet()) {
            copy.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        }
        return copy;
    }

    private ConfigSnapshot buildSnapshot(Map<String, Map<String, ConfigEntry>> mutableData,
                                         long version, Long rollbackOf) {
        // 固化来源数据为不可变结构
        Map<String, Map<String, ConfigEntry>> frozenData = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, ConfigEntry>> e : mutableData.entrySet()) {
            frozenData.put(e.getKey(), Map.copyOf(e.getValue()));
        }
        Map<String, Map<String, ConfigEntry>> immutableData = Map.copyOf(frozenData);

        // 按优先级为每个键预计算回退链
        Map<String, List<SourceEntry>> merged = new LinkedHashMap<>();
        for (String key : registry.all().keySet()) {
            List<SourceEntry> chain = new ArrayList<>();
            for (String source : sourceOrder) {
                ConfigEntry entry = immutableData.getOrDefault(source, Map.of()).get(key);
                if (entry != null) {
                    chain.add(new SourceEntry(source, entry));
                }
            }
            merged.put(key, List.copyOf(chain));
        }
        return new ConfigSnapshot(version, registry.getSchemaVersion(),
                System.currentTimeMillis(), immutableData, Map.copyOf(merged), rollbackOf);
    }
}
