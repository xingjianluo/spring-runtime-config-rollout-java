package com.github.xingjianluo.springruntimeconfigrolloutjava.gray;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;

/**
 * 灰度分桶：对 (配置键, 主体) 做 SHA-256 稳定哈希得到固定桶号 [0,99]。
 * <ul>
 *   <li>桶号与放量比例无关：同一配置版本下，比例从 10 调到 50 再到 100，
 *       同一主体的桶号不变，命中集合单调扩大（10%人群 ⊂ 50%人群 ⊂ 100%人群）；</li>
 *   <li>以配置键参与哈希：不同键之间灰度人群相互独立；</li>
 *   <li>不依赖随机数/时间/节点，任何实例、任何时刻结果完全一致，可复现。</li>
 * </ul>
 */
@Component
public class BucketHasher {

    /** 返回主体在该键上的固定桶号 [0,99]。 */
    public int bucket(String key, String subject) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((key + "|" + subject).getBytes(StandardCharsets.UTF_8));
            int value = ((hash[0] & 0xff) << 24)
                    | ((hash[1] & 0xff) << 16)
                    | ((hash[2] & 0xff) << 8)
                    | (hash[3] & 0xff);
            return Math.floorMod(value, 100);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 给定桶号与放量比例，判定是否命中灰度人群。 */
    public boolean hit(int bucket, int rolloutPercent) {
        if (rolloutPercent >= 100) {
            return true;
        }
        if (rolloutPercent <= 0) {
            return false;
        }
        return bucket < rolloutPercent;
    }
}
