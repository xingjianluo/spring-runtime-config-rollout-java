package com.github.xingjianluo.springruntimeconfigrolloutjava.config;

import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigEntry;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigSnapshot;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigType;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigUpdateException;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.EvaluationOutcome;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.RejectReason;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.RolloutRule;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.source.ConfigSource;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.validation.ConfigValidator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 运行时配置管理核心服务（Spring Bean）。
 * <ul>
 *   <li>当前版本保存在不可变快照中，通过原子引用切换，热更新无中间态；</li>
 *   <li>每次判定在方法入口固定一个快照，灰度未命中沿父版本链确定性回退，
 *       因此同一请求绝不会前后读到两个版本，也不会脏读；</li>
 *   <li>发布/回滚全程校验并在 synchronized 下完成，失败时旧版本保持不变，
 *       异常携带可区分的 {@link RejectReason}；</li>
 *   <li>连续多次变更形成父子版本链；回滚以“目标版本内容 + 目标版本父链”
 *       生成新版本，不破坏既有链路。</li>
 * </ul>
 */
@Service
public class RuntimeConfigService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigService.class);

    private final AtomicReference<ConfigSnapshot> current =
            new AtomicReference<>(ConfigSnapshot.emptyRoot("v0"));
    private final List<ConfigSnapshot> history = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    @PostConstruct
    void init() {
        history.add(current.get());
        log.info("RuntimeConfigService 已启动，初始占位版本={}", current.get().version());
    }

    /** 当前生效版本（不可变快照）。 */
    public ConfigSnapshot current() {
        return current.get();
    }

    /** 历史版本（含当前版本），按发布时间先后排列，用于回滚与审计。 */
    public synchronized List<ConfigSnapshot> history() {
        return List.copyOf(history);
    }

    /**
     * 初始化根版本：来源按优先级合并，全部键必须存在。
     * 根版本是配置模式（schema）：键集合与类型自此固定，且必须全量放量。
     * 根版本只能建立一次；如需重建，请使用回滚或发布新版本。
     */
    public synchronized ConfigSnapshot bootstrap(List<ConfigSource> sources)
            throws ConfigUpdateException {
        if (!"v0".equals(current.get().version()) || !current.get().entries().isEmpty()) {
            throw new ConfigUpdateException(RejectReason.VERSION_CONFLICT,
                    "根版本已经建立，不允许重复 bootstrap；请使用 publish 变更配置");
        }
        Map<String, ConfigEntry> merged = ConfigValidator.mergeSources(sources, List.of());
        Map<String, ConfigEntry> fullEntries = new LinkedHashMap<>();
        for (ConfigEntry e : merged.values()) {
            fullEntries.put(e.key(), new ConfigEntry(e.key(), e.type(), e.value(),
                    RolloutRule.full(), e.source()));
        }
        ConfigSnapshot snapshot =
                new ConfigSnapshot(nextVersion(), "bootstrap from sources",
                        fullEntries, null);
        commit(snapshot);
        log.info("根版本建立: version={}, 键数={}, 来源数={}",
                snapshot.version(), snapshot.entries().size(), sources.size());
        return snapshot;
    }

    /**
     * 发布新版本（灰度或全量）。候选条目必须覆盖模式中的全部键、类型一致。
     *
     * @param entries         候选条目（键集合必须等于模式键集合）
     * @param expectedVersion 乐观锁：必须等于当前版本，否则 VERSION_CONFLICT；
     *                        为 null 时不校验
     * @param note            版本说明
     */
    public synchronized ConfigSnapshot publish(Map<String, ConfigEntry> entries,
                                               String expectedVersion,
                                               String note)
            throws ConfigUpdateException {
        ConfigSnapshot base = current.get();
        checkExpectedVersion(base, expectedVersion);
        if (base.entries().isEmpty()) {
            throw new ConfigUpdateException(RejectReason.DEFAULT_VALUE_MISSING,
                    "尚未建立根版本（配置模式为空），请先 bootstrap");
        }
        Map<String, ConfigEntry> candidate = new LinkedHashMap<>(entries);
        ConfigValidator.validateCompatibility(candidate, base);
        ConfigSnapshot snapshot =
                new ConfigSnapshot(nextVersion(), note == null ? "publish" : note,
                        candidate, base);
        commit(snapshot);
        log.info("新版本发布: version={}, 父版本={}, 键数={}, 说明={}",
                snapshot.version(), base.version(), snapshot.entries().size(), note);
        return snapshot;
    }

    /**
     * 回滚到历史版本：以目标版本的条目生成新版本，父链沿用目标版本的父链，
     * 因此回滚后对任何主体的判定结论与该历史版本当时完全一致。
     */
    public synchronized ConfigSnapshot rollback(String targetVersion,
                                                String expectedVersion)
            throws ConfigUpdateException {
        ConfigSnapshot base = current.get();
        checkExpectedVersion(base, expectedVersion);
        ConfigSnapshot target = history.stream()
                .filter(s -> s.version().equals(targetVersion))
                .findFirst()
                .orElseThrow(() -> new ConfigUpdateException(RejectReason.UNKNOWN_TARGET_VERSION,
                        "回滚目标版本不存在: " + targetVersion));
        ConfigSnapshot snapshot =
                new ConfigSnapshot(nextVersion(),
                        "rollback to " + target.version()
                                + ("[" + target.note() + "]"),
                        new LinkedHashMap<>(target.entries()), target.parent().orElse(null));
        commit(snapshot);
        log.info("版本回滚: 新版本={}, 当前版本={} -> 目标内容={}, 回滚后父版本={}",
                snapshot.version(), base.version(), target.version(),
                target.parent().map(ConfigSnapshot::version).orElse("null"));
        return snapshot;
    }

    /**
     * 打开一个请求级判定视图：进入请求时固定在当前不可变快照上。
     * 同一视图内的所有判定版本一致，且不受并发发布/回滚影响。
     */
    public EvaluationView openView() {
        return new EvaluationView(current.get());
    }

    /**
     * 在当前快照上对单个主体做配置判定（等价于 openView().evaluate(...) 的单次便捷调用）。
     * 若一次请求需要判定多个键并要求版本严格一致，请使用 {@link #openView()}。
     */
    public EvaluationOutcome evaluate(String key, String subject)
            throws ConfigUpdateException {
        return openView().evaluate(key, subject);
    }

    private void checkExpectedVersion(ConfigSnapshot base, String expectedVersion)
            throws ConfigUpdateException {
        if (expectedVersion != null && !expectedVersion.equals(base.version())) {
            throw new ConfigUpdateException(RejectReason.VERSION_CONFLICT,
                    "乐观锁版本不匹配: expected=" + expectedVersion
                            + ", actual=" + base.version());
        }
    }

    private void commit(ConfigSnapshot snapshot) {
        current.set(snapshot);
        history.add(snapshot);
    }

    private String nextVersion() {
        return "v" + sequence.incrementAndGet();
    }

    /** 供管理端/测试构建强类型条目的便捷入口。 */
    public static ConfigEntry entry(String key, ConfigType type, Object value,
                                    int percentage, String salt, String source)
            throws ConfigUpdateException {
        return ConfigValidator.buildEntry(key, type, value,
                new RolloutRule(percentage, salt), source);
    }
}
