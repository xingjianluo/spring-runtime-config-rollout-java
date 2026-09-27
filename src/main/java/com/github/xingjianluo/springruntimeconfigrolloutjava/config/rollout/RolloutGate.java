package com.github.xingjianluo.springruntimeconfigrolloutjava.config.rollout;

import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigEntry;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.RolloutRule;

/**
 * 灰度门：依据稳定桶号判定主体是否落在放量区间。
 * 判定是纯函数：percentage=100 必然全量命中，percentage=0 必然不命中，
 * 中间比例对同一主体永远给出同一结论；放量比例上调时，命中集合只增不减
 * （10% 的命中人群必然包含在 50% 中），从而保证“不同比例灰度人群与全量
 * 人群在同一配置版本下结论一致、可复现”。
 */
public final class RolloutGate {

    private RolloutGate() {
    }

    /**
     * 对主体执行灰度判定。
     *
     * @param entry   配置项（提供 key 与放量规则）
     * @param subject 灰度主体标识
     * @return 判定结果（稳定桶号与是否命中）
     */
    public static Decision evaluate(ConfigEntry entry, String subject) {
        int percentage = entry.rule() == null ? RolloutRule.FULL : entry.rule().percentage();
        String salt = entry.rule() == null ? null : entry.rule().salt();
        long bucket = RolloutHasher.bucket(entry.key(), salt, subject);
        boolean hit = bucket < percentage;
        return new Decision(bucket, hit);
    }

    /**
     * @param bucket     稳定桶号 [0,99]
     * @param hitRollout 是否命中放量区间（bucket &lt; percentage）
     */
    public record Decision(long bucket, boolean hitRollout) {
    }
}
