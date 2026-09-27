package com.github.xingjianluo.springruntimeconfigrolloutjava.domain;

/**
 * 配置项 schema 描述：键名、值类型、是否必填、缺省值与数值范围。
 * 缺省值为 null 表示该键没有缺省值（必填）。
 */
public record ConfigKeyDescriptor(
        String key,
        ConfigType type,
        boolean required,
        String defaultValue,
        Long min,
        Long max
) {
    public boolean hasDefault() {
        return defaultValue != null;
    }
}
