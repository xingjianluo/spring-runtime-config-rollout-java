package com.github.xingjianluo.springruntimeconfigrolloutjava.config.source;

import com.github.xingjianluo.springruntimeconfigrolloutjava.config.RuntimeConfigService;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigUpdateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 应用启动时按优先级引导根版本（可选）。
 * <p>
 * 通过 {@code config.bootstrap.locations} 指定一个或多个配置文件，
 * 列表顺序即优先级从高到低；默认尝试 {@code file:./config/runtime-config.properties}
 * （高优先级）与 classpath 的 {@code runtime-config.properties}（低优先级）。
 * 高优先级文件缺失时静默回退到低优先级（来源回退语义），两者都不存在则跳过引导，
 * 等待管理端 bootstrap/publish；但只要文件存在且内容非法，启动即失败
 * （fail-fast，绝不静默使用部分配置）。
 */
@Component
public class ConfigBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ConfigBootstrap.class);

    private final RuntimeConfigService service;
    private final List<String> locations;

    public ConfigBootstrap(RuntimeConfigService service,
                           @Value("${config.bootstrap.locations:file:./config/runtime-config.properties,classpath:runtime-config.properties}")
                           List<String> locations) {
        this.service = service;
        this.locations = locations;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        List<ConfigSource> sources = new ArrayList<>();
        int priority = locations.size();
        for (String location : locations) {
            Properties props = tryLoad(location.trim());
            if (props != null) {
                sources.add(new PropertiesConfigSource(location, priority, props));
            }
            priority--;
        }
        if (sources.isEmpty()) {
            log.warn("未找到任何引导配置文件（{}），跳过根版本引导，等待管理端推送", locations);
            return;
        }
        try {
            service.bootstrap(sources);
        } catch (ConfigUpdateException e) {
            throw new IllegalStateException(
                    "引导配置非法，拒绝启动: reason=" + e.reason() + ", " + e.getMessage(), e);
        }
    }

    private Properties tryLoad(String location) {
        try {
            Properties props = new Properties();
            if (location.startsWith("classpath:")) {
                String resource = location.substring("classpath:".length());
                ClassPathResource cp = new ClassPathResource(resource);
                if (!cp.exists()) {
                    return null;
                }
                try (InputStream in = cp.getInputStream()) {
                    props.load(in);
                }
            } else if (location.startsWith("file:")) {
                Path path = Path.of(location.substring("file:".length()));
                if (!Files.exists(path)) {
                    return null;
                }
                try (InputStream in = Files.newInputStream(path)) {
                    props.load(in);
                }
            } else {
                log.warn("无法识别的配置位置前缀，已忽略: {}", location);
                return null;
            }
            log.info("加载引导配置来源: {}（条目数={}）", location, props.size());
            return props;
        } catch (Exception e) {
            throw new IllegalStateException("读取配置文件失败: " + location, e);
        }
    }
}
