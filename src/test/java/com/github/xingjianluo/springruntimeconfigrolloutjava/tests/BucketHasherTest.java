package com.github.xingjianluo.springruntimeconfigrolloutjava.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.xingjianluo.springruntimeconfigrolloutjava.gray.BucketHasher;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 灰度分桶：稳定性、单调放量与边界。 */
class BucketHasherTest {

    private static final Logger log = LoggerFactory.getLogger(BucketHasherTest.class);
    private final BucketHasher hasher = new BucketHasher();

    @Test
    void bucketIsStableAndReproducible() {
        int first = hasher.bucket("feature.checkout.enabled", "user-123");
        for (int i = 0; i < 100; i++) {
            assertThat(hasher.bucket("feature.checkout.enabled", "user-123")).isEqualTo(first);
        }
        assertThat(first).isBetween(0, 99);
        log.info("分桶可复现: key=feature.checkout.enabled, subject=user-123, bucket={}", first);
    }

    @Test
    void cohortIsMonotonicWhenRolloutGrows() {
        // 命中集合必须满足 10% 人群 ⊆ 50% 人群 ⊆ 100% 人群
        Set<String> hit10 = new HashSet<>();
        Set<String> hit50 = new HashSet<>();
        for (int i = 0; i < 10000; i++) {
            String subject = "user-" + i;
            int bucket = hasher.bucket("feature.checkout.enabled", subject);
            if (hasher.hit(bucket, 10)) {
                hit10.add(subject);
            }
            if (hasher.hit(bucket, 50)) {
                hit50.add(subject);
            }
        }
        assertThat(hit50).containsAll(hit10);
        log.info("放量单调性: 10%人群={} 人, 50%人群={} 人（10%是50%的子集）",
                hit10.size(), hit50.size());
    }

    @Test
    void hitBoundaries() {
        assertThat(hasher.hit(0, 1)).isTrue();
        assertThat(hasher.hit(99, 1)).isFalse();
        assertThat(hasher.hit(49, 50)).isTrue();
        assertThat(hasher.hit(50, 50)).isFalse();
        assertThat(hasher.hit(0, 0)).isFalse();
        assertThat(hasher.hit(99, 100)).isTrue();
        log.info("灰度边界: bucket=0/1%命中, bucket=99/1%未命中, bucket=49/50%命中, bucket=50/50%未命中");
    }

    @Test
    void distributionIsUniform() {
        int[] counts = new int[100];
        for (int i = 0; i < 100000; i++) {
            counts[hasher.bucket("k", "u" + i)]++;
        }
        for (int c : counts) {
            assertThat(c).isBetween(700, 1300);
        }
        log.info("分桶均匀性: 10 万主体各桶计数在 [700,1300] 内");
    }
}
