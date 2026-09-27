package com.github.xingjianluo.springruntimeconfigrolloutjava.exception;

/** 查询的配置键无法解析（所有来源与缺省值都缺失）。 */
public class ConfigNotFoundException extends RuntimeException {

    private final RejectionReason reason;

    public ConfigNotFoundException(RejectionReason reason, String detail) {
        super(reason.getDescription() + ": " + detail);
        this.reason = reason;
    }

    public RejectionReason getReason() {
        return reason;
    }
}
