package com.github.xingjianluo.springruntimeconfigrolloutjava.config.model;

import java.util.Optional;

/**
 * 单个配置项在某个版本中的定义，整体不可变。
 *
 * @param key     配置键
 * @param type    固定类型
 * @param value   强类型值
 * @param rule    灰度规则
 * @param source  该值的来源名称（用于解释优先级判定）
 */
public record ConfigEntry(String key,
                          ConfigType type,
                          Object value,
                          RolloutRule rule,
                          String source) {

    public Optional<String> salt() {
        return Optional.ofNullable(rule == null ? null : rule.salt());
    }
}
