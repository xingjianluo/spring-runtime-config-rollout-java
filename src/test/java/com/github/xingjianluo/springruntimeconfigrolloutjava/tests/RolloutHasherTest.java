package com.github.xingjianluo.springruntimeconfigrolloutjava.tests;

import com.github.xingjianluo.springruntimeconfigrolloutjava.config.rollout.RolloutHasher;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灰度散列：稳定性、取值范围与分布合理性。
 */
class RolloutHasherTest {

    private static final Logger log = LoggerFactory.getLogger(RolloutHasherTest.class);

    @Test
    void sameInputMustProduceSameBucket() {
        for (int i = 0; i < 1000; i++) {
            long b1 = RolloutHasher.bucket("feature.x", "salt-a", "user-42");
            long b2 = RolloutHasher.bucket("feature.x", "salt-a", "user-42");
            assertEquals(b1, b2, "同一输入的桶号必须可复现");
        }
        long bucket = RolloutHasher.bucket("feature.x", "salt-a", "user-42");
        log.info("[灰度散列] key=feature.x subject=user-42 salt=salt-a 稳定桶号={}", bucket);
    }

    @Test
    void bucketMustStayInRange() {
        for (long i = 0; i < 5000; i++) {
            long bucket = RolloutHasher.bucket("feature.y", null, "subject-" + i);
            assertTrue(bucket >= 0 && bucket < 100, "桶号必须在 [0,99]: " + bucket);
        }
    }

    @Test
    void saltShouldDifferentiateKeysAndDistributionShouldBeReasonablyEven() {
        // 加盐后同一主体在不同配置项上的桶号应有差异（避免所有配置命中同一批人）。
        long noSalt = RolloutHasher.bucket("feature.a", null, "user-7");
        long salted = RolloutHasher.bucket("feature.a", "canary", "user-7");
        log.info("[灰度散列] subject=user-7 无盐桶号={} 盐值canary桶号={}", noSalt, salted);

        long[] freq = new long[100];
        for (long i = 0; i < 20000; i++) {
            freq[(int) RolloutHasher.bucket("feature.z", null, "u" + i)]++;
        }
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (long f : freq) {
            min = Math.min(min, f);
            max = Math.max(max, f);
        }
        // 20000 样本，期望每桶约 200；放宽到 [100,300]，防止极端聚集导致灰度不公平。
        assertTrue(min >= 100 && max <= 300, "桶分布过度倾斜: min=" + min + ", max=" + max);
        assertNotEquals(noSalt, salted, "加盐通常应改变桶号");
        log.info("[灰度散列] 20000 样本分布 min={} max={}", min, max);
    }
}
