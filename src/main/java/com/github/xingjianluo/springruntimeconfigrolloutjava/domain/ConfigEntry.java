package com.github.xingjianluo.springruntimeconfigrolloutjava.domain;

/**
 * 单个配置项在某个来源中的内容：原始字符串值 + 灰度放量比例（0-100）。
 * 100 表示全量，0 表示不对任何人生效（仅作为占位/回退链上的空档位）。
 */
public record ConfigEntry(String value, int rolloutPercent) {
}
