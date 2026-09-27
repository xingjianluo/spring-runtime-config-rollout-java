package com.github.xingjianluo.springruntimeconfigrolloutjava.tests;

import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigEntry;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigSnapshot;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigType;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigUpdateException;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.RejectReason;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.SourceEntry;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.source.ConfigSource;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.source.MapConfigSource;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.validation.ConfigValidator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置来源优先级、回退语义与非法输入拒绝。
 */
class ConfigSourcePriorityTest {

    private static final Logger log =
            LoggerFactory.getLogger(ConfigSourcePriorityTest.class);

    private MapConfigSource source(String name, int priority, SourceEntry... entries) {
        Map<String, SourceEntry> data = new java.util.LinkedHashMap<>();
        for (SourceEntry e : entries) {
            data.put(e.key(), e);
        }
        return new MapConfigSource(name, priority, data);
    }

    @Test
    void higherPriorityWinsAndLowerFillsMissingKeys() throws Exception {
        ConfigSource local = source("local-file", 10,
                new SourceEntry("feature.a", ConfigType.STRING, "from-file"),
                new SourceEntry("feature.b", ConfigType.INTEGER, "1"));
        ConfigSource remote = source("remote", 20,
                new SourceEntry("feature.a", ConfigType.STRING, "from-remote"));

        Map<String, ConfigEntry> merged =
                ConfigValidator.mergeSources(List.of(remote, local), List.of());

        assertEquals("from-remote", merged.get("feature.a").value());
        assertEquals("remote", merged.get("feature.a").source(),
                "同键值冲突时高优先级来源必须胜出");
        assertEquals(1L, merged.get("feature.b").value());
        assertEquals("local-file", merged.get("feature.b").source(),
                "高优先级缺失的键必须回退到低优先级来源");
        log.info("[来源优先级] feature.a 命中来源={} 值={}；feature.b 回退来源={} 值={}",
                merged.get("feature.a").source(), merged.get("feature.a").value(),
                merged.get("feature.b").source(), merged.get("feature.b").value());
    }

    @Test
    void missingRequiredKeyMustBeRejected() {
        ConfigSource s = source("remote", 20,
                new SourceEntry("feature.a", ConfigType.STRING, "x"));
        ConfigUpdateException ex = assertThrows(ConfigUpdateException.class,
                () -> ConfigValidator.mergeSources(List.of(s), List.of("feature.required")));
        assertEquals(RejectReason.DEFAULT_VALUE_MISSING, ex.reason());
        log.info("[缺省值缺失] 拒绝原因={} 说明={}", ex.reason(), ex.getMessage());
    }

    @Test
    void typeConflictAcrossSourcesMustBeRejected() {
        ConfigSource low = source("local", 10,
                new SourceEntry("feature.a", ConfigType.STRING, "hello"));
        ConfigSource high = source("remote", 20,
                new SourceEntry("feature.a", ConfigType.INTEGER, "123"));
        ConfigUpdateException ex = assertThrows(ConfigUpdateException.class,
                () -> ConfigValidator.mergeSources(List.of(high, low), List.of()));
        assertEquals(RejectReason.SOURCE_TYPE_CONFLICT, ex.reason());
        log.info("[来源类型冲突] 拒绝原因={} 说明={}", ex.reason(), ex.getMessage());
    }

    @Test
    void duplicateSourceNameAtSamePriorityMustBeRejected() {
        ConfigSource s1 = source("dup", 10,
                new SourceEntry("a", ConfigType.STRING, "1"));
        ConfigSource s2 = source("dup", 10,
                new SourceEntry("b", ConfigType.STRING, "2"));
        ConfigUpdateException ex = assertThrows(ConfigUpdateException.class,
                () -> ConfigValidator.mergeSources(List.of(s1, s2), List.of()));
        assertEquals(RejectReason.DUPLICATE_SOURCE, ex.reason());
        log.info("[来源重名] 拒绝原因={} 说明={}", ex.reason(), ex.getMessage());
    }

    @Test
    void invalidValuesMustBeRejectedWithDistinctReasons() {
        ConfigUpdateException badInt = assertThrows(ConfigUpdateException.class,
                () -> ConfigValidator.validateEntry(
                        new SourceEntry("a", ConfigType.INTEGER, "not-a-number")));
        assertEquals(RejectReason.TYPE_MISMATCH, badInt.reason());

        ConfigUpdateException nullValue = assertThrows(ConfigUpdateException.class,
                () -> ConfigValidator.validateEntry(
                        new SourceEntry("a", ConfigType.STRING, null)));
        assertEquals(RejectReason.MISSING_VALUE, nullValue.reason());

        ConfigUpdateException blankKey = assertThrows(ConfigUpdateException.class,
                () -> ConfigValidator.validateEntry(
                        new SourceEntry("  ", ConfigType.STRING, "x")));
        assertEquals(RejectReason.INVALID_KEY, blankKey.reason());

        ConfigUpdateException badBool = assertThrows(ConfigUpdateException.class,
                () -> ConfigValidator.validateEntry(
                        new SourceEntry("a", ConfigType.BOOLEAN, "yes")));
        assertEquals(RejectReason.TYPE_MISMATCH, badBool.reason());
        log.info("[非法值] 拒绝原因分别为: {} / {} / {} / {}",
                badInt.reason(), nullValue.reason(), blankKey.reason(), badBool.reason());
    }

    @Test
    void numericJsonInputsAreCoercedExactlyAndFloatsForIntegerRejected() throws Exception {
        // Jackson 会把 JSON 小数解析为 Double：DECIMAL 必须精确接受，不能走二进制浮点尾数。
        ConfigEntry decimal = ConfigValidator.validateEntry(
                new SourceEntry("ratio", ConfigType.DECIMAL, 0.05d));
        assertEquals(new java.math.BigDecimal("0.05"), decimal.value());
        ConfigEntry boolEntry = ConfigValidator.validateEntry(
                new SourceEntry("flag", ConfigType.BOOLEAN, Boolean.TRUE));
        assertEquals(Boolean.TRUE, boolEntry.value());
        ConfigEntry intEntry = ConfigValidator.validateEntry(
                new SourceEntry("count", ConfigType.INTEGER, 42L));
        assertEquals(42L, intEntry.value());

        ConfigUpdateException floatForInt = assertThrows(ConfigUpdateException.class,
                () -> ConfigValidator.validateEntry(
                        new SourceEntry("count", ConfigType.INTEGER, 1.5d)));
        assertEquals(RejectReason.TYPE_MISMATCH, floatForInt.reason());
        log.info("[JSON数值] DECIMAL(0.05) 精确接受、INTEGER(1.5) 拒绝原因={}",
                floatForInt.reason());
    }

    @Test
    void incompatibleTypeChangeAndKeyRemovalMustBeRejected() throws Exception {
        RuntimeConfigServiceFixture fixture = RuntimeConfigServiceFixture.bootstrapped(
                source("base", 10,
                        new SourceEntry("a", ConfigType.STRING, "v1"),
                        new SourceEntry("b", ConfigType.INTEGER, "1")));
        ConfigSnapshot current = fixture.service.current();

        ConfigUpdateException typeChange = assertThrows(ConfigUpdateException.class,
                () -> ConfigValidator.validateCompatibility(Map.of(
                        "a", new ConfigEntry("a", ConfigType.INTEGER, 2L, null, "p"),
                        "b", current.entries().get("b")), current));
        assertEquals(RejectReason.INCOMPATIBLE_TYPE_CHANGE, typeChange.reason());

        ConfigUpdateException removed = assertThrows(ConfigUpdateException.class,
                () -> ConfigValidator.validateCompatibility(Map.of(
                        "a", current.entries().get("a")), current));
        assertEquals(RejectReason.INCOMPATIBLE_KEY_REMOVED, removed.reason());

        ConfigUpdateException newKey = assertThrows(ConfigUpdateException.class,
                () -> ConfigValidator.validateCompatibility(Map.of(
                        "a", current.entries().get("a"),
                        "b", current.entries().get("b"),
                        "c", new ConfigEntry("c", ConfigType.STRING, "x", null, "p")),
                        current));
        assertEquals(RejectReason.ROLLOUT_WITHOUT_BASELINE, newKey.reason());
        log.info("[不兼容变更] 拒绝原因: {} / {} / {}",
                typeChange.reason(), removed.reason(), newKey.reason());
        assertTrue(true);
    }
}
