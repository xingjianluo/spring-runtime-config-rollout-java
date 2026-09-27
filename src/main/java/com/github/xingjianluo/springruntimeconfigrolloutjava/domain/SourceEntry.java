package com.github.xingjianluo.springruntimeconfigrolloutjava.domain;

/** 来源名与该来源上的配置条目，用于按优先级回退判定。 */
public record SourceEntry(String source, ConfigEntry entry) {
}
