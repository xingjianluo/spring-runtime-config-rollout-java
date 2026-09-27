package com.github.xingjianluo.springruntimeconfigrolloutjava.config.model;

/**
 * 灰度放量规则。
 *
 * @param percentage 放量百分比，取值 [0,100]：100 表示全量，0 表示不放量（一律回退）。
 * @param salt       灰度散列盐值，可为空；加盐可避免不同配置项命中同一批人群。
 */
public record RolloutRule(int percentage, String salt) {

    public static final int FULL = 100;

    public RolloutRule {
        // 校验在 validation 包统一进行，骨架阶段不做约束。
    }

    public static RolloutRule full() {
        return new RolloutRule(FULL, null);
    }
}
