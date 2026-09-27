package com.github.xingjianluo.springruntimeconfigrolloutjava.tests;

import com.github.xingjianluo.springruntimeconfigrolloutjava.config.RuntimeConfigService;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigEntry;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigSnapshot;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigType;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigUpdateException;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.EvaluationOutcome;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.RejectReason;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.RolloutRule;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.SourceEntry;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.source.MapConfigSource;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.rollout.RolloutHasher;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行时配置服务：热更新、灰度边界、回滚稳定性与并发一致性。
 */
class RuntimeConfigServiceTest {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigServiceTest.class);

    private static final String KEY = "feature.theme";

    private MapConfigSource baseSource() {
        return new MapConfigSource("base", 10, Map.of(
                KEY, new SourceEntry(KEY, ConfigType.STRING, "light"),
                "feature.n", new SourceEntry("feature.n", ConfigType.INTEGER, "1")));
    }

    private Map<String, ConfigEntry> candidate(String theme, int percentage) throws Exception {
        Map<String, ConfigEntry> map = new LinkedHashMap<>();
        map.put(KEY, RuntimeConfigService.entry(KEY, ConfigType.STRING, theme,
                percentage, "theme-canary", "test"));
        map.put("feature.n", RuntimeConfigService.entry("feature.n", ConfigType.INTEGER,
                percentage == 100 ? 2L : 1L, percentage, null, "test"));
        return map;
    }

    @Test
    void hotUpdateTakesEffectAtomicAndIsReproducible() throws Exception {
        RuntimeConfigServiceFixture f = RuntimeConfigServiceFixture.bootstrapped(baseSource());
        RuntimeConfigService svc = f.service;

        EvaluationOutcome before = svc.evaluate(KEY, "user-1");
        assertEquals("light", before.value());
        assertEquals("v1", before.version());
        log.info("[热更新] 变更前 version={} 人群=user-1 依据={} 值={}",
                before.version(), before.subject(), before.reason(), before.value());

        ConfigSnapshot v2 = svc.publish(candidate("dark", 100), "v1", "full dark");

        EvaluationOutcome after1 = svc.evaluate(KEY, "user-1");
        EvaluationOutcome after2 = svc.evaluate(KEY, "user-1");
        assertEquals("dark", after1.value());
        assertEquals(v2.version(), after1.version());
        assertEquals(after1.value(), after2.value(), "热更新后结论必须稳定可复现");
        assertEquals(after1.version(), after2.version());
        assertEquals(v2.version(), svc.current().version());
        log.info("[热更新] 变更后 version={} 人群=user-1 依据={} 值={}",
                after1.version(), after1.subject(), after1.reason(), after1.value());
    }

    @Test
    void rolloutBoundaryIsConsistentAcrossPercentagesAndFullRelease() throws Exception {
        RuntimeConfigServiceFixture f = RuntimeConfigServiceFixture.bootstrapped(baseSource());
        RuntimeConfigService svc = f.service;

        svc.publish(candidate("dark", 10), "v1", "10% canary");
        // 按稳定桶对主体分层：灰度命中者拿到新值，未命中者确定性回退到 v1 旧值。
        Map<Boolean, List<String>> groups = new LinkedHashMap<>();
        groups.put(true, new java.util.ArrayList<>());
        groups.put(false, new java.util.ArrayList<>());
        for (int i = 0; i < 500; i++) {
            String subject = "user-" + i;
            long bucket = RolloutHasher.bucket(KEY, "theme-canary", subject);
            groups.get(bucket < 10).add(subject);
        }
        List<String> hit = groups.get(true);
        List<String> miss = groups.get(false);
        assertTrue(hit.size() > 30 && hit.size() < 70, "10% 灰度命中数量异常: " + hit.size());
        log.info("[灰度边界] 10% 放量：命中 {} 人，未命中 {} 人", hit.size(), miss.size());

        for (String s : hit) {
            EvaluationOutcome o = svc.evaluate(KEY, s);
            assertEquals("dark", o.value(), "命中灰度的主体必须得到新值: " + s);
            assertTrue(o.hitRollout());
            assertEquals("v2", o.version());
        }
        for (String s : miss) {
            EvaluationOutcome o = svc.evaluate(KEY, s);
            assertEquals("light", o.value(), "未命中主体必须确定性回退旧值: " + s);
            assertFalse(o.hitRollout());
            assertEquals("v1", o.version(), "回退结论必须报告基线版本");
        }
        EvaluationOutcome sampleHit = svc.evaluate(KEY, hit.get(0));
        EvaluationOutcome sampleMiss = svc.evaluate(KEY, miss.get(0));
        log.info("[灰度命中] version={} subject={} bucket={} percentage={} 依据={} 值={}",
                sampleHit.version(), sampleHit.subject(), sampleHit.bucket(),
                sampleHit.percentage(), sampleHit.reason(), sampleHit.value());
        log.info("[灰度回退] 回退版本={} subject={} bucket={} percentage={} 依据={} 值={}",
                sampleMiss.version(), sampleMiss.subject(), sampleMiss.bucket(),
                sampleMiss.percentage(), sampleMiss.reason(), sampleMiss.value());

        // 放量到 50%：原 10% 命中集合必须是 50% 命中集合的子集（单调性）。
        svc.publish(candidate("dark", 50), "v2", "50% canary");
        for (String s : hit) {
            assertEquals("dark", svc.evaluate(KEY, s).value(),
                    "比例上调后原有命中人群结论必须保持一致: " + s);
        }
        int stillFallback = 0;
        for (String s : miss) {
            if ("light".equals(svc.evaluate(KEY, s).value())) {
                stillFallback++;
            }
        }
        assertTrue(stillFallback > 150, "50% 放量时约一半人仍应回退");
        log.info("[灰度扩量] 50% 放量：原未命中人群中仍回退 {} 人，原命中人群结论不变",
                stillFallback);

        // 全量：所有主体拿到新值，且与之前各阶段命中人群结论一致。
        ConfigSnapshot full = svc.publish(candidate("dark", 100), "v3", "full");
        for (int i = 0; i < 500; i++) {
            EvaluationOutcome o = svc.evaluate(KEY, "user-" + i);
            assertEquals("dark", o.value());
            assertEquals(full.version(), o.version());
            assertEquals(100, o.percentage());
        }
        log.info("[灰度全量] version={} 全部 500 主体命中全量，结论与灰度阶段一致",
                full.version());
    }

    @Test
    void invalidPublishMustBeRejectedEntirelyAndOldVersionStays() throws Exception {
        RuntimeConfigServiceFixture f = RuntimeConfigServiceFixture.bootstrapped(baseSource());
        RuntimeConfigService svc = f.service;

        // 非法百分比 + 合法条目混合：整体拒绝，当前版本保持 v1。
        // 此处绕过便捷构造器（它会提前拒绝非法输入），模拟候选版本进入服务校验。
        Map<String, ConfigEntry> bad = new LinkedHashMap<>(candidate("dark", 50));
        bad.put(KEY, new ConfigEntry(KEY, ConfigType.STRING, "dark",
                new RolloutRule(101, "theme-canary"), "test"));
        ConfigUpdateException badPct = assertThrows(ConfigUpdateException.class,
                () -> svc.publish(bad, "v1", "bad percentage"));
        assertEquals(RejectReason.INVALID_PERCENTAGE, badPct.reason());
        assertEquals("v1", svc.current().version());
        assertEquals("light", svc.evaluate(KEY, "user-1").value());

        // 乐观锁：基于过期版本发布会被拒绝。
        svc.publish(candidate("dark", 100), "v1", "full");
        ConfigUpdateException conflict = assertThrows(ConfigUpdateException.class,
                () -> svc.publish(candidate("dark", 100), "v1", "stale"));
        assertEquals(RejectReason.VERSION_CONFLICT, conflict.reason());

        // 类型不兼容变更：拒绝且旧值不动。
        Map<String, ConfigEntry> typeChanged = new LinkedHashMap<>();
        typeChanged.put(KEY, RuntimeConfigService.entry(KEY, ConfigType.INTEGER, 1L,
                100, null, "test"));
        typeChanged.put("feature.n", RuntimeConfigService.entry("feature.n",
                ConfigType.INTEGER, 2L, 100, null, "test"));
        ConfigUpdateException incompatible = assertThrows(ConfigUpdateException.class,
                () -> svc.publish(typeChanged, svc.current().version(), "type change"));
        assertEquals(RejectReason.INCOMPATIBLE_TYPE_CHANGE, incompatible.reason());
        assertEquals("dark", svc.evaluate(KEY, "user-1").value());

        log.info("[非法发布] 拒绝原因: {} / {} / {}；旧版本保持 {} 未被部分生效",
                badPct.reason(), conflict.reason(), incompatible.reason(),
                svc.current().version());
    }

    @Test
    void rollbackMustRestoreStableAndExplainableDecisions() throws Exception {
        RuntimeConfigServiceFixture f = RuntimeConfigServiceFixture.bootstrapped(baseSource());
        RuntimeConfigService svc = f.service;

        svc.publish(candidate("dark", 10), "v1", "10%");
        svc.publish(candidate("dark", 100), "v2", "full dark");
        svc.publish(candidate("blue", 100), "v3", "full blue");
        assertEquals("blue", svc.evaluate(KEY, "user-9").value());

        // 回滚到 v2（10% 灰度版本）：灰度结构恢复，未命中者回到 light，命中者得到 dark。
        ConfigSnapshot rolled = svc.rollback("v2", "v4");
        assertTrue(rolled.note().contains("v2"));
        String darkSubject = null;
        String lightSubject = null;
        for (int i = 0; i < 200; i++) {
            String s = "rb-" + i;
            long bucket = RolloutHasher.bucket(KEY, "theme-canary", s);
            String expected = bucket < 10 ? "dark" : "light";
            EvaluationOutcome o = svc.evaluate(KEY, s);
            assertEquals(expected, o.value(), "回滚后判定必须与目标历史版本一致: " + s);
            if (expected.equals("dark") && darkSubject == null) {
                darkSubject = s;
            }
            if (expected.equals("light") && lightSubject == null) {
                lightSubject = s;
            }
        }
        EvaluationOutcome darkOutcome = svc.evaluate(KEY, darkSubject);
        EvaluationOutcome lightOutcome = svc.evaluate(KEY, lightSubject);
        log.info("[回滚] 新版本={} 回滚至内容=v1；命中 subject={} version={} 值={} 依据={}",
                rolled.version(), darkSubject, darkOutcome.version(),
                darkOutcome.value(), darkOutcome.reason());
        log.info("[回滚] 未命中 subject={} 回退版本={} 值={} 依据={}",
                lightSubject, lightOutcome.version(), lightOutcome.value(),
                lightOutcome.reason());

        // 回滚不存在的版本必须被拒绝。
        ConfigUpdateException missing = assertThrows(ConfigUpdateException.class,
                () -> svc.rollback("v999", rolled.version()));
        assertEquals(RejectReason.UNKNOWN_TARGET_VERSION, missing.reason());
        log.info("[回滚] 非法目标拒绝原因={}", missing.reason());
    }

    @Test
    void concurrentReadsNeverObserveIntermediateState() throws Exception {
        RuntimeConfigServiceFixture f = RuntimeConfigServiceFixture.bootstrapped(baseSource());
        RuntimeConfigService svc = f.service;

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger writersDone = new AtomicInteger();
        ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<EvaluationOutcome> samples = new ConcurrentLinkedQueue<>();

        // 4 个读线程：每个“请求”打开一个视图并在其中连续判定两次，
        // 同一视图内值与版本必须完全一致（请求期间不读到更新中间态）。
        for (int r = 0; r < 4; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 3000; i++) {
                        String subject = "u" + (i % 200);
                        try (var view = svc.openView()) {
                            EvaluationOutcome o1 = view.evaluate(KEY, subject);
                            EvaluationOutcome o2 = view.evaluate(KEY, subject);
                            if (!o1.value().equals(o2.value()) || !o1.version().equals(o2.version())) {
                                violations.add("同一请求读到不同版本: " + o1.version()
                                        + "/" + o2.version());
                            }
                            if (i % 900 == 0) {
                                samples.add(o1);
                            }
                        }
                    }
                } catch (Exception e) {
                    violations.add(e.toString());
                }
            });
        }
        // 2 个写线程：轮流灰度/全量发布，版本单调推进。
        for (int w = 0; w < 2; w++) {
            final int wid = w;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 20; i++) {
                        int pct = (i + wid) % 2 == 0 ? 100 : 30;
                        String theme = (i % 2 == 0) ? "dark" : "light";
                        synchronized (svc) {
                            String expected = svc.current().version();
                            svc.publish(candidate(theme, pct), expected,
                                    "concurrent-" + wid + "-" + i);
                        }
                        writersDone.incrementAndGet();
                    }
                } catch (Exception e) {
                    violations.add("writer: " + e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务超时");
        assertEquals(40, writersDone.get(), "所有发布都应成功（写者串行化）");
        assertTrue(violations.isEmpty(), "发现脏读/中间态: " + violations);
        for (EvaluationOutcome o : samples) {
            assertTrue(List.of("light", "dark").contains(o.value()));
        }
        log.info("[并发] 40 次连续发布、24000 次判定，无中间态/脏读；样例版本={}",
                samples.stream().map(EvaluationOutcome::version).distinct().toList());
    }

    @Test
    void unknownKeyAndEmptySubjectMustBeRejected() throws Exception {
        RuntimeConfigServiceFixture f = RuntimeConfigServiceFixture.bootstrapped(baseSource());
        ConfigUpdateException noKey = assertThrows(ConfigUpdateException.class,
                () -> f.service.evaluate("not.exist", "u1"));
        assertEquals(RejectReason.UNKNOWN_KEY, noKey.reason());
        ConfigUpdateException noSubject = assertThrows(ConfigUpdateException.class,
                () -> f.service.evaluate(KEY, "  "));
        assertEquals(RejectReason.INVALID_KEY, noSubject.reason());
        log.info("[异常输入] 未知键拒绝={} 空主体拒绝={}", noKey.reason(), noSubject.reason());
    }
}
