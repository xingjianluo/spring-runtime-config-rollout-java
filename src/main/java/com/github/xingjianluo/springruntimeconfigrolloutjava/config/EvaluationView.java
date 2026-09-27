package com.github.xingjianluo.springruntimeconfigrolloutjava.config;

import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigEntry;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigSnapshot;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigUpdateException;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.EvaluationOutcome;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.RejectReason;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.RolloutRule;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.rollout.RolloutGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 请求级配置判定视图：在视图创建时绑定一个不可变快照，
 * 同一请求内无论判定多少次、期间发生多少次发布/回滚，
 * 读到的版本与结论都不会变化，杜绝更新中间态与脏读。
 *
 * <pre>
 *   try (var view = service.openView()) {  // 本请求固定在某个版本
 *       var a = view.evaluate("key1", user);
 *       var b = view.evaluate("key2", user); // 与 a 必然同一版本
 *   }
 * </pre>
 */
public final class EvaluationView implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EvaluationView.class);

    private final ConfigSnapshot snapshot;

    EvaluationView(ConfigSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    /** 请求结束：无可释放资源，仅用于 try-with-resources 表达请求边界。 */
    @Override
    public void close() {
        // no-op：快照本身不可变且无外部资源。
    }

    /** 本视图绑定的配置版本。 */
    public String version() {
        return snapshot.version();
    }

    /** 在绑定快照上判定配置。未命中灰度时沿父版本链确定性回退。 */
    public EvaluationOutcome evaluate(String key, String subject)
            throws ConfigUpdateException {
        if (subject == null || subject.isBlank()) {
            throw new ConfigUpdateException(RejectReason.INVALID_KEY,
                    "灰度主体标识为空: subject=" + subject);
        }
        return evaluateOn(snapshot, key, subject);
    }

    private EvaluationOutcome evaluateOn(ConfigSnapshot node, String key, String subject)
            throws ConfigUpdateException {
        ConfigEntry entry = node.entries().get(key);
        if (entry == null) {
            if (node.parent().isPresent()) {
                return evaluateOn(node.parent().get(), key, subject);
            }
            throw new ConfigUpdateException(RejectReason.UNKNOWN_KEY,
                    "配置键不存在: key=" + key + ", 判定版本=" + snapshot.version());
        }
        RolloutGate.Decision decision = RolloutGate.evaluate(entry, subject);
        int percentage = entry.rule() == null ? RolloutRule.FULL : entry.rule().percentage();
        if (decision.hitRollout()) {
            String reason = "命中版本 " + node.version() + " 的放量区间: bucket="
                    + decision.bucket() + " < percentage=" + percentage
                    + (percentage == RolloutRule.FULL ? "（全量）" : "（灰度）");
            EvaluationOutcome outcome = new EvaluationOutcome(key, subject, entry.value(),
                    entry.type(), node.version(), decision.bucket(), true,
                    percentage, entry.source(), reason);
            log.info("配置判定 version={} key={} subject={} hitRollout=true bucket={} "
                            + "percentage={} value={} source={} reason={}",
                    outcome.version(), key, subject, decision.bucket(), percentage,
                    entry.value(), entry.source(), reason);
            return outcome;
        }
        if (node.parent().isPresent()) {
            EvaluationOutcome fallback = evaluateOn(node.parent().get(), key, subject);
            String reason = "版本 " + node.version() + " 灰度未命中: bucket="
                    + decision.bucket() + " >= percentage=" + percentage
                    + "，确定性回退到父版本 " + fallback.version();
            EvaluationOutcome wrapped = new EvaluationOutcome(key, subject, fallback.value(),
                    fallback.type(), fallback.version(), decision.bucket(), false,
                    percentage, fallback.source(), reason);
            log.info("配置判定 viewVersion={} key={} subject={} hitRollout=false bucket={} "
                            + "percentage={} fallbackVersion={} value={} reason={}",
                    snapshot.version(), key, subject, decision.bucket(), percentage,
                    fallback.version(), fallback.value(), reason);
            return wrapped;
        }
        throw new ConfigUpdateException(RejectReason.DEFAULT_VALUE_MISSING,
                "配置键在根版本未全量且无回退基线: key=" + key);
    }
}
