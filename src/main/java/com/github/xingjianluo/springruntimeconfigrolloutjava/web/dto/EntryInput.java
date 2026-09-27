package com.github.xingjianluo.springruntimeconfigrolloutjava.web.dto;

/** 管理接口入参：单个配置项。 */
public record EntryInput(String key, String value, Integer rolloutPercent) {
}
