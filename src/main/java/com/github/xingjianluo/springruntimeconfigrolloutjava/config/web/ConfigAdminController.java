package com.github.xingjianluo.springruntimeconfigrolloutjava.config.web;

import com.github.xingjianluo.springruntimeconfigrolloutjava.config.RuntimeConfigService;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigEntry;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigSnapshot;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigType;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigUpdateException;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.EvaluationOutcome;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.RolloutRule;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.validation.ConfigValidator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置管理与判定的最小 HTTP 接口。
 * <ul>
 *   <li>GET  /config/evaluate/{key}?subject=u123 —— 热更新判定；</li>
 *   <li>GET  /config/current、/config/history —— 版本审计；</li>
 *   <li>POST /config/publish —— 灰度/全量发布（原子生效，非法整体拒绝）；</li>
 *   <li>POST /config/rollback —— 按版本回滚。</li>
 * </ul>
 */
@RestController
@RequestMapping("/config")
public class ConfigAdminController {

    private final RuntimeConfigService service;

    public ConfigAdminController(RuntimeConfigService service) {
        this.service = service;
    }

    /** 判定接口：GET /config/evaluate/{key}?subject=u123 */
    @GetMapping("/evaluate/{key}")
    public EvaluationOutcome evaluate(@PathVariable String key,
                                      @RequestParam String subject)
            throws ConfigUpdateException {
        return service.evaluate(key, subject);
    }

    /** 当前版本信息。 */
    @GetMapping("/current")
    public Map<String, Object> current() {
        ConfigSnapshot snapshot = service.current();
        return snapshotView(snapshot);
    }

    /** 历史版本列表。 */
    @GetMapping("/history")
    public List<Map<String, Object>> history() {
        return service.history().stream().map(this::snapshotView).toList();
    }

    /** 灰度/全量发布。请求体见 {@link PublishRequest}。 */
    @PostMapping("/publish")
    public Map<String, Object> publish(@RequestBody PublishRequest request)
            throws ConfigUpdateException {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new ConfigUpdateException(
                    com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.RejectReason.MISSING_VALUE,
                    "发布内容为空");
        }
        Map<String, ConfigEntry> entries = new LinkedHashMap<>();
        for (PublishItem item : request.items()) {
            ConfigType type = parseType(item.type(), item.key());
            int percentage = item.percentage() == null ? RolloutRule.FULL : item.percentage();
            ConfigEntry entry = ConfigValidator.buildEntry(item.key(), type, item.value(),
                    new RolloutRule(percentage, item.salt()), "http-publish");
            entries.put(entry.key(), entry);
        }
        ConfigSnapshot snapshot = service.publish(entries, request.expectedVersion(),
                request.note());
        return snapshotView(snapshot);
    }

    /** 回滚。 */
    @PostMapping("/rollback")
    public Map<String, Object> rollback(@RequestBody RollbackRequest request)
            throws ConfigUpdateException {
        if (request == null || request.targetVersion() == null || request.targetVersion().isBlank()) {
            throw new ConfigUpdateException(
                    com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.RejectReason.UNKNOWN_TARGET_VERSION,
                    "回滚目标版本为空");
        }
        return snapshotView(service.rollback(request.targetVersion(), request.expectedVersion()));
    }

    private ConfigType parseType(String text, String key) throws ConfigUpdateException {
        if (text == null || text.isBlank()) {
            throw new ConfigUpdateException(
                    com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.RejectReason.TYPE_MISMATCH,
                    "发布条目缺少类型声明: key=" + key);
        }
        try {
            return ConfigType.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ConfigUpdateException(
                    com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.RejectReason.TYPE_MISMATCH,
                    "未知配置类型: key=" + key + ", type=" + text);
        }
    }

    private Map<String, Object> snapshotView(ConfigSnapshot snapshot) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("version", snapshot.version());
        view.put("note", snapshot.note());
        view.put("parentVersion", snapshot.parent().map(ConfigSnapshot::version).orElse(null));
        Map<String, Object> keys = new LinkedHashMap<>();
        for (ConfigEntry e : snapshot.entries().values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", e.type());
            item.put("value", e.value());
            item.put("percentage", e.rule() == null ? RolloutRule.FULL : e.rule().percentage());
            item.put("salt", e.rule() == null ? null : e.rule().salt());
            item.put("source", e.source());
            keys.put(e.key(), item);
        }
        view.put("entries", keys);
        return view;
    }

    /** 拒绝统一返回 422 + 可区分原因码，调用方不会拿到模糊错误。 */
    @ExceptionHandler(ConfigUpdateException.class)
    public ResponseEntity<Map<String, Object>> handle(ConfigUpdateException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rejected", true);
        body.put("reason", ex.reason().name());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    public record PublishRequest(String expectedVersion,
                                 String note,
                                 List<PublishItem> items) {
    }

    public record PublishItem(String key,
                              String type,
                              Object value,
                              Integer percentage,
                              String salt) {
    }

    public record RollbackRequest(String targetVersion, String expectedVersion) {
    }
}
