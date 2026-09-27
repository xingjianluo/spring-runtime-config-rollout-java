package com.github.xingjianluo.springruntimeconfigrolloutjava.config.model;

/**
 * 配置变更非法时抛出的受检异常，携带可区分的 {@link RejectReason}。
 * 受检语义：调用方必须显式处理拒绝，不允许静默忽略。
 */
public class ConfigUpdateException extends Exception {

    private final RejectReason reason;

    public ConfigUpdateException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason reason() {
        return reason;
    }
}
