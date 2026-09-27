package com.github.xingjianluo.springruntimeconfigrolloutjava.config.model;

/**
 * 配置变更/查询被拒绝的可区分原因。
 */
public enum RejectReason {
    /** 配置键为空或非法。 */
    INVALID_KEY,
    /** 配置值为空。 */
    MISSING_VALUE,
    /** 值与声明类型不匹配或无法解析。 */
    TYPE_MISMATCH,
    /** 灰度百分比超出 [0,100]。 */
    INVALID_PERCENTAGE,
    /** 多个同优先级来源声明同名来源。 */
    DUPLICATE_SOURCE,
    /** 不同来源对同一键给出了不同类型。 */
    SOURCE_TYPE_CONFLICT,
    /** 必填键在所有来源中都缺失。 */
    DEFAULT_VALUE_MISSING,
    /** 新版本相对已建立的模式发生了类型变化（不兼容变更）。 */
    INCOMPATIBLE_TYPE_CHANGE,
    /** 新版本缺少已建立模式中的键。 */
    INCOMPATIBLE_KEY_REMOVED,
    /** 灰度版本引入了新模式中不存在、且无历史基线的键。 */
    ROLLOUT_WITHOUT_BASELINE,
    /** 乐观锁版本不匹配（基于旧版本发布/回滚）。 */
    VERSION_CONFLICT,
    /** 回滚目标版本不存在。 */
    UNKNOWN_TARGET_VERSION,
    /** 查询/判定的配置键不存在。 */
    UNKNOWN_KEY
}
