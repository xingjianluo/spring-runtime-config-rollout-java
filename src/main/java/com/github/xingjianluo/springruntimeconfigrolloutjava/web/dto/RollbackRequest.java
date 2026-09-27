package com.github.xingjianluo.springruntimeconfigrolloutjava.web.dto;

/** 管理接口入参：把指定来源回退到 targetVersion 时该来源的内容。 */
public record RollbackRequest(String source, long targetVersion) {
}
