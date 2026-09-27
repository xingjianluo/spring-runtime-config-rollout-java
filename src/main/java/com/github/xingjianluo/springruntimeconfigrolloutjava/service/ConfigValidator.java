package com.github.xingjianluo.springruntimeconfigrolloutjava.service;

import com.github.xingjianluo.springruntimeconfigrolloutjava.domain.ConfigEntry;
import com.github.xingjianluo.springruntimeconfigrolloutjava.domain.ConfigKeyDescriptor;
import com.github.xingjianluo.springruntimeconfigrolloutjava.domain.ConfigType;
import com.github.xingjianluo.springruntimeconfigrolloutjava.exception.ConfigRejectedException;
import com.github.xingjianluo.springruntimeconfigrolloutjava.exception.RejectionReason;
import com.github.xingjianluo.springruntimeconfigrolloutjava.registry.ConfigSchemaRegistry;
import com.github.xingjianluo.springruntimeconfigrolloutjava.web.dto.EntryInput;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 配置内容校验器：把非法变更挡在提交之前。
 * 校验分为两层：
 * <ol>
 *   <li>来源级：未知键、同来源重复键、值类型不符、整数越界、灰度比例越界；</li>
 *   <li>快照级：结构版本不一致、多个“全量生效”来源对同一键给出冲突值、
 *       必填键在所有来源与缺省值中均缺失。</li>
 * </ol>
 * 任何一项不通过都整体拒绝本次变更，当前生效快照保持不变，绝不部分生效。
 */
@Component
public class ConfigValidator {

    private final ConfigSchemaRegistry registry;

    public ConfigValidator(ConfigSchemaRegistry registry) {
        this.registry = registry;
    }

    /** 校验单个来源的输入条目（未知键/重复键/类型/范围/灰度比例）。 */
    public void validateSourcePayload(String source, List<EntryInput> entries) {
        Set<String> seen = new HashSet<>();
        for (EntryInput input : entries) {
            if (input == null || input.key() == null || input.key().isBlank()
                    || input.value() == null) {
                throw new ConfigRejectedException(RejectionReason.ILLEGAL_PAYLOAD,
                        "来源[" + source + "]存在 key/value 缺失的条目");
            }
            if (input.rolloutPercent() == null) {
                throw new ConfigRejectedException(RejectionReason.INVALID_ROLLOUT_PERCENT,
                        "键[" + input.key() + "]缺少 rolloutPercent");
            }
            if (!seen.add(input.key())) {
                throw new ConfigRejectedException(RejectionReason.DUPLICATE_KEY_IN_SOURCE,
                        "来源[" + source + "]内配置键重复: " + input.key());
            }
            ConfigKeyDescriptor descriptor = registry.find(input.key())
                    .orElseThrow(() -> new ConfigRejectedException(RejectionReason.UNKNOWN_KEY,
                            "键[" + input.key() + "]未在 schema v"
                                    + registry.getSchemaVersion() + " 中注册"));
            int percent = input.rolloutPercent();
            if (percent < 0 || percent > 100) {
                throw new ConfigRejectedException(RejectionReason.INVALID_ROLLOUT_PERCENT,
                        "键[" + input.key() + "]灰度比例 " + percent + " 超出 [0,100]");
            }
            validateValue(descriptor, input.value());
        }
    }

    private void validateValue(ConfigKeyDescriptor descriptor, String rawValue) {
        if (descriptor.type() == ConfigType.BOOLEAN) {
            if (!rawValue.equalsIgnoreCase("true") && !rawValue.equalsIgnoreCase("false")) {
                throw new ConfigRejectedException(RejectionReason.INVALID_VALUE_TYPE,
                        "键[" + descriptor.key() + "]需要 BOOLEAN，实际=" + rawValue);
            }
        } else if (descriptor.type() == ConfigType.INTEGER) {
            long parsed;
            try {
                parsed = Long.parseLong(rawValue);
            } catch (NumberFormatException e) {
                throw new ConfigRejectedException(RejectionReason.INVALID_VALUE_TYPE,
                        "键[" + descriptor.key() + "]需要 INTEGER，实际=" + rawValue);
            }
            if (descriptor.min() != null && parsed < descriptor.min()
                    || descriptor.max() != null && parsed > descriptor.max()) {
                throw new ConfigRejectedException(RejectionReason.VALUE_OUT_OF_RANGE,
                        "键[" + descriptor.key() + "]值 " + parsed + " 超出范围 ["
                                + descriptor.min() + "," + descriptor.max() + "]");
            }
        }
        // STRING 不做进一步限制
    }

    /** 校验提交后整体快照的结构版本、来源冲突与必填缺省语义。 */
    public void validateSnapshot(int schemaVersion,
                                 Map<String, Map<String, ConfigEntry>> sourceData) {
        if (schemaVersion != registry.getSchemaVersion()) {
            throw new ConfigRejectedException(RejectionReason.SCHEMA_VERSION_INCOMPATIBLE,
                    "结构版本 " + schemaVersion + " 与当前支持版本 "
                            + registry.getSchemaVersion() + " 不兼容");
        }
        // 同一键若被多个“全量生效(100%)”来源定义，值必须一致，否则冲突必须明确拒绝。
        for (String key : registry.all().keySet()) {
            String fullValue = null;
            String fullSource = null;
            for (Map.Entry<String, Map<String, ConfigEntry>> source : sourceData.entrySet()) {
                ConfigEntry entry = source.getValue().get(key);
                if (entry != null && entry.rolloutPercent() == 100) {
                    if (fullValue != null && !fullValue.equals(entry.value())) {
                        throw new ConfigRejectedException(RejectionReason.SOURCE_CONFLICT,
                                "键[" + key + "]被全量来源[" + fullSource + "]=" + fullValue
                                        + " 与[" + source.getKey() + "]=" + entry.value()
                                        + " 同时定义且取值冲突");
                    }
                    fullValue = entry.value();
                    fullSource = source.getKey();
                }
            }
        }
        // 必填键：至少一个来源提供值，或 schema 提供缺省值；否则查询将无解，提前拒绝。
        for (ConfigKeyDescriptor descriptor : registry.all().values()) {
            if (descriptor.required() && !descriptor.hasDefault()) {
                boolean provided = sourceData.values().stream()
                        .anyMatch(m -> m.containsKey(descriptor.key()));
                if (!provided) {
                    throw new ConfigRejectedException(RejectionReason.MISSING_REQUIRED_DEFAULT,
                            "必填键[" + descriptor.key() + "]无缺省值且没有任何来源提供");
                }
            }
        }
    }
}
