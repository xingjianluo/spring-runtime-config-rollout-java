package com.github.xingjianluo.springruntimeconfigrolloutjava.config.validation;

import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigEntry;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigSnapshot;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigType;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigUpdateException;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.RejectReason;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.RolloutRule;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.SourceEntry;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.source.ConfigSource;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置校验与来源合并：所有非法输入在此被显式拒绝（受检异常 + 可区分原因），
 * 保证不会产生“部分生效”的新版本。
 * <p>
 * 合并语义：来源按 priority 升序处理，高优先级覆盖低优先级；同优先级来源
 * 不允许重名；同键在不同来源中的类型必须一致（值冲突由优先级决定，不报错；
 * 类型冲突无法解释，直接拒绝）。
 */
public final class ConfigValidator {

    private ConfigValidator() {
    }

    /**
     * 校验原始条目并转换为“全量放量”的强类型条目（来源名为空）。
     */
    public static ConfigEntry validateEntry(SourceEntry raw) throws ConfigUpdateException {
        return buildEntry(raw.key(), raw.type(), raw.rawValue(), RolloutRule.full(), null);
    }

    /**
     * 校验并构建强类型条目。
     *
     * @param key      配置键，不能为空白
     * @param type     声明类型，不能为 null
     * @param rawValue 原始值，不能为 null/空白，且必须可转为声明类型
     * @param rule     灰度规则，null 视为全量；百分比必须在 [0,100]
     * @param source   值来源名称（可空）
     */
    public static ConfigEntry buildEntry(String key,
                                         ConfigType type,
                                         Object rawValue,
                                         RolloutRule rule,
                                         String source) throws ConfigUpdateException {
        if (key == null || key.isBlank()) {
            throw new ConfigUpdateException(RejectReason.INVALID_KEY,
                    "配置键为空或非法: key=" + key);
        }
        if (type == null) {
            throw new ConfigUpdateException(RejectReason.TYPE_MISMATCH,
                    "配置项缺少类型声明: key=" + key);
        }
        Object value = coerce(key, type, rawValue);
        RolloutRule effectiveRule = rule == null ? RolloutRule.full() : rule;
        int percentage = effectiveRule.percentage();
        if (percentage < 0 || percentage > RolloutRule.FULL) {
            throw new ConfigUpdateException(RejectReason.INVALID_PERCENTAGE,
                    "灰度百分比超出 [0,100]: key=" + key + ", percentage=" + percentage);
        }
        return new ConfigEntry(key, type, value, effectiveRule, source);
    }

    private static Object coerce(String key, ConfigType type, Object rawValue)
            throws ConfigUpdateException {
        if (rawValue == null) {
            throw new ConfigUpdateException(RejectReason.MISSING_VALUE,
                    "配置值缺失: key=" + key);
        }
        String text = rawValue instanceof String s ? s.trim() : null;
        if (text != null && text.isEmpty()) {
            throw new ConfigUpdateException(RejectReason.MISSING_VALUE,
                    "配置值为空白: key=" + key);
        }
        try {
            return switch (type) {
                case STRING -> rawValue instanceof String sv ? sv : String.valueOf(rawValue);
                case INTEGER -> toLong(key, rawValue, text);
                case BOOLEAN -> rawValue instanceof Boolean b
                        ? b
                        : parseBoolean(key, text);
                case DECIMAL -> toDecimal(rawValue, text);
            };
        } catch (ConfigUpdateException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ConfigUpdateException(RejectReason.TYPE_MISMATCH,
                    "配置值无法解析为声明类型: key=" + key
                            + ", 声明类型=" + type + ", 原始值=" + rawValue);
        }
    }

    /**
     * 整型转换：接受整数形态的 Number 与数字字符串；
     * 浮点形态（JSON 小数、Float/Double）一律拒绝，避免静默截断。
     */
    private static Long toLong(String key, Object rawValue, String text)
            throws ConfigUpdateException {
        if (rawValue instanceof Number n) {
            double d = n.doubleValue();
            if (d != Math.floor(d) || Double.isInfinite(d)) {
                throw new ConfigUpdateException(RejectReason.TYPE_MISMATCH,
                        "整型配置收到了小数值: key=" + key + ", 原始值=" + rawValue);
            }
            return n.longValue();
        }
        return Long.parseLong(text);
    }

    /**
     * 小数转换：BigDecimal 原样；其它 Number 按其字符串形态精确构造
     * （如 JSON 的 Double 0.05 得到精确的 0.05，而非二进制浮点尾数）；
     * 字符串直接解析。
     */
    private static BigDecimal toDecimal(Object rawValue, String text) {
        if (rawValue instanceof BigDecimal bd) {
            return bd;
        }
        if (rawValue instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return new BigDecimal(text);
    }

    private static Boolean parseBoolean(String key, String text) throws ConfigUpdateException {
        if ("true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        throw new ConfigUpdateException(RejectReason.TYPE_MISMATCH,
                "布尔配置值只能为 true/false: key=" + key + ", 原始值=" + text);
    }

    /**
     * 按优先级合并多个来源。
     *
     * @param sources      来源列表，priority 越大越优先
     * @param requiredKeys 合并结果中必须存在的键；任一缺失即拒绝（缺省值缺失）
     * @return 键 -&gt; 强类型条目（全部为全量放量，携带获胜来源名）
     */
    public static Map<String, ConfigEntry> mergeSources(List<ConfigSource> sources,
                                                        List<String> requiredKeys)
            throws ConfigUpdateException {
        if (sources == null || sources.isEmpty()) {
            throw new ConfigUpdateException(RejectReason.DEFAULT_VALUE_MISSING,
                    "没有任何配置来源，无法建立基线");
        }
        // 同优先级下来源名必须唯一，避免优先级解释出现歧义。
        Map<Integer, Set<String>> namesByPriority = new LinkedHashMap<>();
        for (ConfigSource source : sources) {
            Set<String> names = namesByPriority.computeIfAbsent(
                    source.priority(), k -> new HashSet<>());
            if (!names.add(source.name())) {
                throw new ConfigUpdateException(RejectReason.DUPLICATE_SOURCE,
                        "优先级 " + source.priority() + " 下存在重复来源: "
                                + source.name());
            }
        }

        // 升序处理，高优先级覆盖低优先级；跨来源同键类型必须一致。
        List<ConfigSource> ordered = sources.stream()
                .sorted(Comparator.comparingInt(ConfigSource::priority))
                .toList();
        Map<String, ConfigEntry> merged = new LinkedHashMap<>();
        for (ConfigSource source : ordered) {
            for (SourceEntry raw : source.load()) {
                ConfigEntry entry = validateEntry(raw);
                ConfigEntry existing = merged.get(entry.key());
                if (existing != null && existing.type() != entry.type()) {
                    throw new ConfigUpdateException(RejectReason.SOURCE_TYPE_CONFLICT,
                            "配置键在不同来源中类型冲突: key=" + entry.key()
                                    + ", 来源=" + existing.source() + " 类型=" + existing.type()
                                    + " vs 来源=" + source.name() + " 类型=" + entry.type());
                }
                merged.put(entry.key(), new ConfigEntry(entry.key(), entry.type(),
                        entry.value(), entry.rule(), source.name()));
            }
        }

        for (String required : requiredKeys) {
            if (!merged.containsKey(required)) {
                throw new ConfigUpdateException(RejectReason.DEFAULT_VALUE_MISSING,
                        "必填配置键在所有来源中缺失: key=" + required);
            }
        }
        return merged;
    }

    /**
     * 校验候选版本相对已发布版本的兼容性。
     * 兼容范围（以根版本建立的模式 schema 为准）：
     * <ul>
     *   <li>模式中的每个键都必须存在，不允许删键；</li>
     *   <li>键的类型必须保持不变；</li>
     *   <li>不允许引入模式之外的新键（无历史基线，灰度未命中时无法确定性回退）；</li>
     *   <li>灰度百分比合法。</li>
     * </ul>
     */
    public static void validateCompatibility(Map<String, ConfigEntry> candidate,
                                             ConfigSnapshot current)
            throws ConfigUpdateException {
        ConfigSnapshot root = current;
        while (root.parent().isPresent()) {
            root = root.parent().get();
        }
        for (ConfigEntry schemaEntry : root.entries().values()) {
            ConfigEntry next = candidate.get(schemaEntry.key());
            if (next == null) {
                throw new ConfigUpdateException(RejectReason.INCOMPATIBLE_KEY_REMOVED,
                        "新版本删除了既有配置键（不兼容变更）: key=" + schemaEntry.key());
            }
            if (next.type() != schemaEntry.type()) {
                throw new ConfigUpdateException(RejectReason.INCOMPATIBLE_TYPE_CHANGE,
                        "配置项类型发生变化（不兼容变更）: key=" + schemaEntry.key()
                                + ", 既有类型=" + schemaEntry.type()
                                + ", 新类型=" + next.type());
            }
            int percentage = next.rule() == null ? RolloutRule.FULL : next.rule().percentage();
            if (percentage < 0 || percentage > RolloutRule.FULL) {
                throw new ConfigUpdateException(RejectReason.INVALID_PERCENTAGE,
                        "灰度百分比超出 [0,100]: key=" + next.key()
                                + ", percentage=" + percentage);
            }
        }
        for (String key : candidate.keySet()) {
            if (!root.entries().containsKey(key)) {
                throw new ConfigUpdateException(RejectReason.ROLLOUT_WITHOUT_BASELINE,
                        "新配置键没有历史基线，无法灰度放量: key=" + key);
            }
        }
    }
}
