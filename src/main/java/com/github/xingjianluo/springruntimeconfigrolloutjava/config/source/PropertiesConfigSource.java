package com.github.xingjianluo.springruntimeconfigrolloutjava.config.source;

import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigType;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.SourceEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 基于 {@link Properties} 的配置来源。
 * 键名形如 {@code feature.foo=STRING:on}（TYPE:value）：
 * <ul>
 *   <li>冒号前为类型名（STRING/INTEGER/BOOLEAN/DECIMAL），缺省为 STRING；</li>
 *   <li>冒号后为原始值；若值本身以冒号开头（如时间字符串），类型须显式给出。</li>
 * </ul>
 * 此处仅做语法切分，值是否合法交由 ConfigValidator 统一判定，
 * 非法值不会在来源层被静默丢弃。
 */
public class PropertiesConfigSource implements ConfigSource {

    private final String name;
    private final int priority;
    private final Properties properties;

    public PropertiesConfigSource(String name, int priority, Properties properties) {
        this.name = name;
        this.priority = priority;
        this.properties = properties;
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
        List<SourceEntry> result = new ArrayList<>();
        for (Map.Entry<Object, Object> e : properties.entrySet()) {
            String key = String.valueOf(e.getKey()).trim();
            String raw = String.valueOf(e.getValue());
            ConfigType type = ConfigType.STRING;
            String value = raw;
            int colon = raw.indexOf(':');
            if (colon >= 0) {
                String prefix = raw.substring(0, colon).trim();
                try {
                    type = ConfigType.valueOf(prefix.toUpperCase());
                    value = raw.substring(colon + 1);
                } catch (IllegalArgumentException unknownType) {
                    // 不是已知类型前缀：整串按 STRING 值处理。
                    type = ConfigType.STRING;
                    value = raw;
                }
            }
            result.add(new SourceEntry(key, type, value));
        }
        return result;
    }
}
