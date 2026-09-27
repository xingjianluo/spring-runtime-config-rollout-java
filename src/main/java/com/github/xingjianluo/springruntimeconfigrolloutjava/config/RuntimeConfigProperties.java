package com.github.xingjianluo.springruntimeconfigrolloutjava.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 运行时配置模块自身的引导属性（与被托管的业务配置无关）。 */
@ConfigurationProperties(prefix = "runtime-config")
public class RuntimeConfigProperties {

    /** 来源优先级，索引越小优先级越高。 */
    private List<String> sources = List.of("database", "file", "default");

    /** 保留的历史版本数量（用于回退）。 */
    private int historySize = 20;

    public List<String> getSources() {
        return sources;
    }

    public void setSources(List<String> sources) {
        this.sources = sources;
    }

    public int getHistorySize() {
        return historySize;
    }

    public void setHistorySize(int historySize) {
        this.historySize = historySize;
    }
}
