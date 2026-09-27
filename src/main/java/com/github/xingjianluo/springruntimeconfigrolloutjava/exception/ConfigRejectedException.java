package com.github.xingjianluo.springruntimeconfigrolloutjava.exception;

/** 配置变更被明确拒绝；携带可区分的原因码与详情。 */
public class ConfigRejectedException extends RuntimeException {

    private final RejectionReason reason;

    public ConfigRejectedException(RejectionReason reason, String detail) {
        super(reason.getDescription() + ": " + detail);
        this.reason = reason;
    }

    public RejectionReason getReason() {
        return reason;
    }
}
