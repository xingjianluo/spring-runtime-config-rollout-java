package com.github.xingjianluo.springruntimeconfigrolloutjava.web.dto;

/** 拒绝/错误响应体，原因码可被调用方区分。 */
public record RejectionResponse(boolean rejected, String reason, String detail, long currentVersion) {
}
