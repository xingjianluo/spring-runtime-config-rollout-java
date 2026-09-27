package com.github.xingjianluo.springruntimeconfigrolloutjava.config.model;

/**
 * 一次配置判定的完整结论，整体不可变，保证可复现、可解释。
 *
 * @param key          配置键
 * @param subject      灰度主体标识
 * @param value        最终生效值
 * @param type         值类型
 * @param version      判定所基于的配置版本（请求期间固定，不会读到中间态）
 * @param bucket       主体散列桶 [0,99]
 * @param hitRollout   是否命中当前版本灰度人群；false 表示回退到父版本
 * @param percentage   当前版本放量百分比
 * @param source       值来源名称
 * @param reason       判定依据的人类可读说明
 */
public record EvaluationOutcome(String key,
                                String subject,
                                Object value,
                                ConfigType type,
                                String version,
                                long bucket,
                                boolean hitRollout,
                                int percentage,
                                String source,
                                String reason) {
}
