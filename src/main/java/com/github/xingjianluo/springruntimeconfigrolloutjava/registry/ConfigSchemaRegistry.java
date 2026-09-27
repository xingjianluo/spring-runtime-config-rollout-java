package com.github.xingjianluo.springruntimeconfigrolloutjava.registry;

import com.github.xingjianluo.springruntimeconfigrolloutjava.domain.ConfigKeyDescriptor;
import com.github.xingjianluo.springruntimeconfigrolloutjava.domain.ConfigType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 配置键 schema 注册表：当前结构版本与全部键描述。
 * 新老配置结构兼容性以 SCHEMA_VERSION + 键集合 + 值类型共同界定，
 * 未知键、类型不匹配、结构版本号不一致都会在提交时被拒绝。
 */
@Component
public class ConfigSchemaRegistry {

    /** 当前支持的配置结构版本；结构演进时递增。 */
    public static final int SCHEMA_VERSION = 1;

    private final Map<String, ConfigKeyDescriptor> descriptors;

    public ConfigSchemaRegistry() {
        Map<String, ConfigKeyDescriptor> map = new LinkedHashMap<>();
        register(map, new ConfigKeyDescriptor(
                "feature.checkout.enabled", ConfigType.BOOLEAN, false, "false", null, null));
        register(map, new ConfigKeyDescriptor(
                "feature.checkout.timeout-ms", ConfigType.INTEGER, false, "5000", 0L, 60000L));
        register(map, new ConfigKeyDescriptor(
                "feature.recommend.strategy", ConfigType.STRING, false, "stable", null, null));
        register(map, new ConfigKeyDescriptor(
                "feature.search.page-size", ConfigType.INTEGER, true, null, 1L, 100L));
        this.descriptors = Map.copyOf(map);
    }

    private static void register(Map<String, ConfigKeyDescriptor> map, ConfigKeyDescriptor d) {
        map.put(d.key(), d);
    }

    public int getSchemaVersion() {
        return SCHEMA_VERSION;
    }

    public Optional<ConfigKeyDescriptor> find(String key) {
        return Optional.ofNullable(descriptors.get(key));
    }

    public Map<String, ConfigKeyDescriptor> all() {
        return descriptors;
    }
}
