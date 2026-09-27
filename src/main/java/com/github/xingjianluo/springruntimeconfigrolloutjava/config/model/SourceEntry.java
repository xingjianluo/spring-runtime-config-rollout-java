package com.github.xingjianluo.springruntimeconfigrolloutjava.config.model;

/**
 * 配置来源提供的原始条目：声明类型 + 原始值（字符串或强类型值）。
 *
 * @param key       配置键
 * @param type      声明类型
 * @param rawValue  原始值，不得为 null
 */
public record SourceEntry(String key, ConfigType type, Object rawValue) {
}
