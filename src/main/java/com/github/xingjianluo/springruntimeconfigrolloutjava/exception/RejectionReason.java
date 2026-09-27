package com.github.xingjianluo.springruntimeconfigrolloutjava.exception;

/** 配置变更被拒绝或查询失败的可区分原因码。 */
public enum RejectionReason {
    UNKNOWN_SOURCE("来源未注册"),
    DUPLICATE_KEY_IN_SOURCE("同一来源内存在重复配置键"),
    UNKNOWN_KEY("配置键未在 schema 中注册，结构不兼容"),
    SCHEMA_VERSION_INCOMPATIBLE("配置结构版本不兼容"),
    MISSING_REQUIRED_DEFAULT("必填配置缺少缺省值且所有来源均未提供"),
    INVALID_VALUE_TYPE("配置值类型与 schema 不符"),
    VALUE_OUT_OF_RANGE("配置值超出允许范围"),
    INVALID_ROLLOUT_PERCENT("灰度比例必须在 0-100 之间"),
    SOURCE_CONFLICT("高优先级来源冲突（同一键出现多个有效值）"),
    VERSION_CONFLICT("提交版本号过期，存在并发变更"),
    VERSION_NOT_FOUND("回退目标版本不存在"),
    ILLEGAL_PAYLOAD("请求体非法或字段缺失");

    private final String description;

    RejectionReason(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
