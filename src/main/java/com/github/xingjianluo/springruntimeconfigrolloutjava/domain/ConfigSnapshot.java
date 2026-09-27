package com.github.xingjianluo.springruntimeconfigrolloutjava.domain;

import java.util.List;
import java.util.Map;

/**
 * 不可变配置快照：一次成功提交产生一个新版本。
 * sourceData：source -> (key -> entry)，保存各来源的完整内容；
 * merged：key -> 按优先级从高到低排列的来源条目，供查询时 O(来源数) 回退。
 * rollbackOf：若非空，表示本版本是回退到该历史版本后重新发布的。
 */
public record ConfigSnapshot(
        long version,
        int schemaVersion,
        long createdAt,
        Map<String, Map<String, ConfigEntry>> sourceData,
        Map<String, List<SourceEntry>> merged,
        Long rollbackOf
) {
}
