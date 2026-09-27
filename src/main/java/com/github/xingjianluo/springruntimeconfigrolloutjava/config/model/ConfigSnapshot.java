package com.github.xingjianluo.springruntimeconfigrolloutjava.config.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 配置版本快照：某一时刻完整、不可变的配置集合。
 * <p>
 * parent 链用于灰度未命中时的确定性回退；根版本 parent 为 null。
 * 快照一经构建，其内容永不改变，因此读请求持有一个快照引用即可保证
 * “同一请求前后读到同一版本”，与并发更新完全隔离。
 */
public final class ConfigSnapshot {

    private final String version;
    private final String note;
    private final Map<String, ConfigEntry> entries;
    private final ConfigSnapshot parent;

    public ConfigSnapshot(String version,
                          String note,
                          Map<String, ConfigEntry> entries,
                          ConfigSnapshot parent) {
        this.version = version;
        this.note = note;
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        this.parent = parent;
    }

    /** 构建空的根版本（服务启动占位，尚未建立任何配置模式）。 */
    public static ConfigSnapshot emptyRoot(String version) {
        return new ConfigSnapshot(version, "empty bootstrap", new LinkedHashMap<>(), null);
    }

    public String version() {
        return version;
    }

    public String note() {
        return note;
    }

    public Map<String, ConfigEntry> entries() {
        return entries;
    }

    public Optional<ConfigEntry> entry(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    public Optional<ConfigSnapshot> parent() {
        return Optional.ofNullable(parent);
    }

    public boolean isRoot() {
        return parent == null;
    }
}
