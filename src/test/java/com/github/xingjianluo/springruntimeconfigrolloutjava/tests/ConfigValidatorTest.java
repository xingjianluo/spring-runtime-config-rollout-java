package com.github.xingjianluo.springruntimeconfigrolloutjava.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.xingjianluo.springruntimeconfigrolloutjava.domain.ConfigEntry;
import com.github.xingjianluo.springruntimeconfigrolloutjava.exception.ConfigRejectedException;
import com.github.xingjianluo.springruntimeconfigrolloutjava.exception.RejectionReason;
import com.github.xingjianluo.springruntimeconfigrolloutjava.registry.ConfigSchemaRegistry;
import com.github.xingjianluo.springruntimeconfigrolloutjava.service.ConfigValidator;
import com.github.xingjianluo.springruntimeconfigrolloutjava.web.dto.EntryInput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 非法配置必须被明确拒绝，且每类输入对应可区分的原因码。 */
class ConfigValidatorTest {

    private static final Logger log = LoggerFactory.getLogger(ConfigValidatorTest.class);
    private final ConfigValidator validator = new ConfigValidator(new ConfigSchemaRegistry());

    private void expect(List<EntryInput> entries, RejectionReason reason) {
        assertThatThrownBy(() -> validator.validateSourcePayload("database", entries))
                .isInstanceOf(ConfigRejectedException.class)
                .extracting(ex -> ((ConfigRejectedException) ex).getReason())
                .isEqualTo(reason);
        log.info("拒绝原因可区分: reason={}, detail={}", reason,
                reason.getDescription());
    }

    @Test
    void unknownKeyRejected() {
        expect(List.of(new EntryInput("not.registered", "true", 100)),
                RejectionReason.UNKNOWN_KEY);
    }

    @Test
    void badTypeRejected() {
        expect(List.of(new EntryInput("feature.checkout.enabled", "maybe", 100)),
                RejectionReason.INVALID_VALUE_TYPE);
        expect(List.of(new EntryInput("feature.checkout.timeout-ms", "abc", 100)),
                RejectionReason.INVALID_VALUE_TYPE);
    }

    @Test
    void outOfRangeRejected() {
        expect(List.of(new EntryInput("feature.checkout.timeout-ms", "999999", 100)),
                RejectionReason.VALUE_OUT_OF_RANGE);
    }

    @Test
    void badRolloutPercentRejected() {
        expect(List.of(new EntryInput("feature.recommend.strategy", "stable", 101)),
                RejectionReason.INVALID_ROLLOUT_PERCENT);
        expect(List.of(new EntryInput("feature.recommend.strategy", "stable", -1)),
                RejectionReason.INVALID_ROLLOUT_PERCENT);
    }

    @Test
    void duplicateKeyRejected() {
        expect(List.of(
                new EntryInput("feature.recommend.strategy", "stable", 100),
                new EntryInput("feature.recommend.strategy", "fresh", 50)),
                RejectionReason.DUPLICATE_KEY_IN_SOURCE);
    }

    @Test
    void missingRequiredDefaultRejectedAtSnapshot() {
        // 必填键 page-size 无缺省值，且没有任何来源提供 -> 快照级拒绝
        assertThatThrownBy(() -> validator.validateSnapshot(
                ConfigSchemaRegistry.SCHEMA_VERSION,
                Map.of("database", Map.of("feature.checkout.enabled",
                        new ConfigEntry("true", 100)))))
                .isInstanceOf(ConfigRejectedException.class)
                .extracting(ex -> ((ConfigRejectedException) ex).getReason())
                .isEqualTo(RejectionReason.MISSING_REQUIRED_DEFAULT);
        log.info("快照级拒绝: 必填键 feature.search.page-size 无缺省值且无来源提供");
    }

    @Test
    void conflictingFullRolloutSourcesRejected() {
        Map<String, Map<String, ConfigEntry>> data = Map.of(
                "database", Map.of("feature.recommend.strategy", new ConfigEntry("a", 100)),
                "file", Map.of("feature.recommend.strategy", new ConfigEntry("b", 100)));
        assertThatThrownBy(() -> validator
                .validateSnapshot(ConfigSchemaRegistry.SCHEMA_VERSION, data))
                .isInstanceOf(ConfigRejectedException.class)
                .extracting(ex -> ((ConfigRejectedException) ex).getReason())
                .isEqualTo(RejectionReason.SOURCE_CONFLICT);
        log.info("冲突拒绝: database/file 对同一全量键给出不同值");
    }

    @Test
    void incompatibleSchemaVersionRejected() {
        assertThatThrownBy(() -> validator.validateSnapshot(99, Map.of()))
                .isInstanceOf(ConfigRejectedException.class)
                .extracting(ex -> ((ConfigRejectedException) ex).getReason())
                .isEqualTo(RejectionReason.SCHEMA_VERSION_INCOMPATIBLE);
    }

    @Test
    void validPayloadPasses() {
        assertThatCode(() -> validator.validateSourcePayload("database", List.of(
                new EntryInput("feature.checkout.enabled", "true", 50),
                new EntryInput("feature.checkout.timeout-ms", "3000", 100),
                new EntryInput("feature.recommend.strategy", "fresh", 10),
                new EntryInput("feature.search.page-size", "20", 100)))).doesNotThrowAnyException();
        assertThat((Object) null).isNull();
        log.info("合法载荷通过校验: 4 个键, 含不同灰度比例");
    }
}
