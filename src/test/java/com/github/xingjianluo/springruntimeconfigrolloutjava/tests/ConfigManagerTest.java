package com.github.xingjianluo.springruntimeconfigrolloutjava.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.xingjianluo.springruntimeconfigrolloutjava.config.RuntimeConfigProperties;
import com.github.xingjianluo.springruntimeconfigrolloutjava.domain.ConfigSnapshot;
import com.github.xingjianluo.springruntimeconfigrolloutjava.domain.DecisionResult;
import com.github.xingjianluo.springruntimeconfigrolloutjava.exception.ConfigRejectedException;
import com.github.xingjianluo.springruntimeconfigrolloutjava.exception.RejectionReason;
import com.github.xingjianluo.springruntimeconfigrolloutjava.gray.BucketHasher;
import com.github.xingjianluo.springruntimeconfigrolloutjava.registry.ConfigSchemaRegistry;
import com.github.xingjianluo.springruntimeconfigrolloutjava.service.ConfigManager;
import com.github.xingjianluo.springruntimeconfigrolloutjava.service.ConfigValidator;
import com.github.xingjianluo.springruntimeconfigrolloutjava.web.dto.EntryInput;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 热更新、灰度边界、来源回退、版本一致性、并发与回滚场景。 */
class ConfigManagerTest {

    private static final Logger log = LoggerFactory.getLogger(ConfigManagerTest.class);
    private static final String KEY = "feature.recommend.strategy";

    private final BucketHasher hasher = new BucketHasher();

    private ConfigManager newManager() {
        RuntimeConfigProperties props = new RuntimeConfigProperties();
        return new ConfigManager(props, new ConfigValidator(new ConfigSchemaRegistry()),
                new ConfigSchemaRegistry(), hasher);
    }

    private List<EntryInput> entries(String value, int percent) {
        return List.of(
                new EntryInput(KEY, value, percent),
                new EntryInput("feature.search.page-size", "20", 100));
    }

    /** 找一个在指定键上桶号落在目标桶的主体。 */
    private String subjectAtBucket(int targetBucket) {
        for (int i = 0; i < 1_000_000; i++) {
            String s = "user-" + i;
            if (hasher.bucket(KEY, s) == targetBucket) {
                return s;
            }
        }
        throw new IllegalStateException("no subject");
    }

    @Test
    void hotUpdateTakesEffectWithoutRestart() {
        ConfigManager manager = newManager();
        manager.updateSource("database", entries("v1", 100), null);
        DecisionResult r1 = manager.resolve(KEY, "anyone");
        assertThat(r1.value()).isEqualTo("v1");
        long v1Version = r1.version();

        manager.updateSource("database", entries("v2", 100), null);
        DecisionResult r2 = manager.resolve(KEY, "anyone");
        assertThat(r2.value()).isEqualTo("v2");
        assertThat(r2.version()).isGreaterThan(v1Version);
        log.info("热更新: version={} value=v1 -> version={} value=v2, 判定依据={}",
                v1Version, r2.version(), r2.basis());
    }

    @Test
    void grayBoundaryAndReproducibleDecision() {
        ConfigManager manager = newManager();
        manager.updateSource("database", entries("gray-value", 50), null);

        String in = subjectAtBucket(49);
        String out = subjectAtBucket(50);
        DecisionResult hit = manager.resolve(KEY, in);
        DecisionResult miss = manager.resolve(KEY, out);

        assertThat(hit.found()).isTrue();
        assertThat(hit.value()).isEqualTo("gray-value");
        assertThat(hit.cohortHit()).isTrue();
        assertThat(hit.rolloutPercent()).isEqualTo(50);
        // 未进灰度人群 -> 回退到 schema 缺省值
        assertThat(miss.found()).isTrue();
        assertThat(miss.value()).isEqualTo("stable");
        assertThat(miss.source()).isEqualTo("SCHEMA_DEFAULT");

        // 可复现：同一主体连续查询结论与版本一致
        for (int i = 0; i < 20; i++) {
            assertThat(manager.resolve(KEY, in).value()).isEqualTo("gray-value");
            assertThat(manager.resolve(KEY, out).value()).isEqualTo("stable");
        }
        log.info("灰度边界: 主体{} bucket=49 命中人群GRAY value={}; 主体{} bucket=50 未命中, "
                        + "回退缺省值={}; version={}",
                in, hit.value(), out, miss.value(), hit.version());
    }

    @Test
    void sameCohortAcrossRolloutPercentages() {
        ConfigManager manager = newManager();
        String subject = subjectAtBucket(5);
        manager.updateSource("database", entries("x", 10), null);
        assertThat(manager.resolve(KEY, subject).value()).isEqualTo("x");
        manager.updateSource("database", entries("x", 50), null);
        assertThat(manager.resolve(KEY, subject).value()).isEqualTo("x");
        manager.updateSource("database", entries("x", 100), null);
        assertThat(manager.resolve(KEY, subject).value()).isEqualTo("x");
        log.info("放量 10%->50%->100% 同一主体(bucket=5)始终命中，结论一致可复现");
    }

    @Test
    void priorityFallbackAcrossSources() {
        ConfigManager manager = newManager();
        // database 只对 10% 灰度；file 全量旧值
        manager.updateSource("file", List.of(
                new EntryInput(KEY, "old-full", 100),
                new EntryInput("feature.search.page-size", "20", 100)), null);
        manager.updateSource("database", entries("new-gray", 10), null);

        String grayUser = subjectAtBucket(3);
        String other = subjectAtBucket(80);
        assertThat(manager.resolve(KEY, grayUser).value()).isEqualTo("new-gray");
        DecisionResult fallback = manager.resolve(KEY, other);
        assertThat(fallback.value()).isEqualTo("old-full");
        assertThat(fallback.source()).isEqualTo("file");
        log.info("来源优先级回退: 灰度主体{}命中高优先级 database={}; 其他主体回退 file={}, 依据={}",
                grayUser, "new-gray", fallback.value(), fallback.basis());
    }

    @Test
    void invalidUpdateIsRejectedAndOldValueStays() {
        ConfigManager manager = newManager();
        manager.updateSource("database", entries("good", 100), null);
        long versionBefore = manager.currentSnapshot().version();

        assertThatThrownBy(() -> manager.updateSource("database",
                List.of(new EntryInput(KEY, "not-an-int-scenario-but-string-is-fine", 500)),
                null))
                .isInstanceOf(ConfigRejectedException.class)
                .extracting(ex -> ((ConfigRejectedException) ex).getReason())
                .isEqualTo(RejectionReason.INVALID_ROLLOUT_PERCENT);

        assertThat(manager.currentSnapshot().version()).isEqualTo(versionBefore);
        assertThat(manager.resolve(KEY, "anyone").value()).isEqualTo("good");
        log.info("非法变更被整体拒绝: version 仍为={}, 旧值继续生效, 无部分生效", versionBefore);
    }

    @Test
    void unknownSourceRejected() {
        ConfigManager manager = newManager();
        assertThatThrownBy(() -> manager.updateSource("nacos", entries("x", 100), null))
                .isInstanceOf(ConfigRejectedException.class)
                .extracting(ex -> ((ConfigRejectedException) ex).getReason())
                .isEqualTo(RejectionReason.UNKNOWN_SOURCE);
        log.info("未知来源被拒绝: reason=UNKNOWN_SOURCE");
    }

    @Test
    void optimisticVersionConflictRejected() {
        ConfigManager manager = newManager();
        manager.updateSource("database", entries("v1", 100), null);
        long v = manager.currentSnapshot().version();
        // 先有一次别的提交
        manager.updateSource("database", entries("v2", 100), null);
        assertThatThrownBy(() -> manager.updateSource("database", entries("v3", 100), v))
                .isInstanceOf(ConfigRejectedException.class)
                .extracting(ex -> ((ConfigRejectedException) ex).getReason())
                .isEqualTo(RejectionReason.VERSION_CONFLICT);
        assertThat(manager.resolve(KEY, "anyone").value()).isEqualTo("v2");
        log.info("乐观锁: 基于过期版本 {} 的提交被拒绝, 当前保持 v2", v);
    }

    @Test
    void rollbackRepublishesHistoricalContentAsNewVersion() {
        ConfigManager manager = newManager();
        manager.updateSource("database", entries("v1", 100), null);
        long versionOfV1 = manager.currentSnapshot().version();
        manager.updateSource("database", entries("v2", 100), null);
        assertThat(manager.resolve(KEY, "anyone").value()).isEqualTo("v2");

        ConfigSnapshot rolled = manager.rollbackSource("database", versionOfV1);
        assertThat(rolled.rollbackOf()).isEqualTo(versionOfV1);
        assertThat(rolled.version()).isGreaterThan(versionOfV1);
        assertThat(manager.resolve(KEY, "anyone").value()).isEqualTo("v1");
        log.info("回滚: 回退到 version={} 的内容并发布为新版本 version={}, 判定稳定",
                versionOfV1, rolled.version());
    }

    @Test
    void rollbackToUnknownVersionRejected() {
        ConfigManager manager = newManager();
        assertThatThrownBy(() -> manager.rollbackSource("database", 999L))
                .isInstanceOf(ConfigRejectedException.class)
                .extracting(ex -> ((ConfigRejectedException) ex).getReason())
                .isEqualTo(RejectionReason.VERSION_NOT_FOUND);
    }

    @Test
    void concurrentUpdatesAndReadsNeverSeeIntermediateState() throws InterruptedException {
        ConfigManager manager = newManager();
        manager.updateSource("database", entries("v0", 100), null);

        int rounds = 300;
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        List<Throwable> readerErrors = new ArrayList<>();

        Thread updater = new Thread(() -> {
            try {
                start.await();
                for (int i = 1; i <= rounds; i++) {
                    manager.updateSource("database", entries("v" + i, 100), null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                stop.set(true);
            }
        });

        Thread reader = new Thread(() -> {
            try {
                start.await();
                while (!stop.get()) {
                    // 单次 resolve 内必须自洽；返回值必须严格等于 vN 形式之一
                    DecisionResult r = manager.resolve(KEY, "anyone");
                    if (!r.value().matches("v\\d+")) {
                        readerErrors.add(new AssertionError("脏值: " + r.value()));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        updater.start();
        reader.start();
        start.countDown();
        updater.join();
        reader.join();

        assertThat(readerErrors).isEmpty();
        DecisionResult finalResult = manager.resolve(KEY, "anyone");
        assertThat(finalResult.value()).isEqualTo("v" + rounds);
        log.info("并发: {} 次原子更新期间读者无脏读/无中间态, 最终 version={} value={}",
                rounds, finalResult.version(), finalResult.value());
    }
}
