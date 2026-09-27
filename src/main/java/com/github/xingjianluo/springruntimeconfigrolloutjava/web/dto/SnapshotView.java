package com.github.xingjianluo.springruntimeconfigrolloutjava.web.dto;

import java.util.Map;

/** 当前快照的只读视图。 */
public record SnapshotView(long version, int schemaVersion, long createdAt, Long rollbackOf,
                           Map<String, Map<String, String>> values) {
}
