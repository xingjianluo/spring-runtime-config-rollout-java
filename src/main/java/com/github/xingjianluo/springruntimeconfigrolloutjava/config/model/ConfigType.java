package com.github.xingjianluo.springruntimeconfigrolloutjava.config.model;

/**
 * 配置项值类型。任何配置项在首次建立（根版本）后类型即固定，
 * 后续变更必须保持同类型，否则拒绝发布。
 */
public enum ConfigType {
    STRING,
    INTEGER,
    BOOLEAN,
    DECIMAL
}
