package com.github.xingjianluo.springruntimeconfigrolloutjava.config.rollout;

import java.nio.charset.StandardCharsets;

/**
 * 灰度分桶散列。要求：对同一 (key, salt, subject) 输入永远得到同一桶，
 * 不同比例灰度人群与全量人群的判定单调一致、可复现。
 * <p>
 * 采用 FNV-1a 64-bit（无符号取模 100），输出 [0,99] 的稳定桶号。
 * FNV 分布均匀且实现零依赖，便于跨版本复现。
 */
public final class RolloutHasher {

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final long BUCKETS = 100L;

    private RolloutHasher() {
    }

    /**
     * 计算 [0,99] 的稳定桶号。
     *
     * @param key     配置键
     * @param salt    散列盐值，可为 null
     * @param subject 灰度主体标识（用户 ID / 设备 ID / 租户 ID 等）
     */
    public static long bucket(String key, String salt, String subject) {
        String material = key + ' ' + (salt == null ? "" : salt) + ' ' + subject;
        long hash = FNV_OFFSET_BASIS;
        for (byte b : material.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xffL);
            hash *= FNV_PRIME;
        }
        return Long.remainderUnsigned(hash, BUCKETS);
    }
}
