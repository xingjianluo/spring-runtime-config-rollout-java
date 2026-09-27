package com.github.xingjianluo.springruntimeconfigrolloutjava.config.source;

import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.SourceEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于内存 Map 的配置来源，主要用于管理端推送与测试。
 */
public class MapConfigSource implements ConfigSource {

    private final String name;
    private final int priority;
    private final Map<String, SourceEntry> data;

    public MapConfigSource(String name, int priority, Map<String, SourceEntry> data) {
        this.name = name;
        this.priority = priority;
        this.data = data;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public List<SourceEntry> load() {
        return new ArrayList<>(data.values());
    }
}
