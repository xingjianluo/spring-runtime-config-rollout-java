package com.github.xingjianluo.springruntimeconfigrolloutjava.config.source;

import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.SourceEntry;

import java.util.List;

/**
 * 配置来源。priority 数值越大优先级越高；同优先级出现重名来源将被拒绝。
 */
public interface ConfigSource {

    /** 来源名称（用于优先级解释与冲突诊断）。 */
    String name();

    /** 优先级，数值越大越优先。 */
    int priority();

    /** 该来源当前提供的全部原始条目。 */
    List<SourceEntry> load();
}
