package com.github.xingjianluo.springruntimeconfigrolloutjava.web.dto;

import java.util.List;

/**
 * 管理接口入参：用 entries 全量替换某个来源的内容。
 * expectedVersion 可选，用于乐观并发控制；不传则不做版本校验。
 */
public record UpdateRequest(String source, List<EntryInput> entries, Long expectedVersion,
                            Integer schemaVersion) {
}
