package com.github.xingjianluo.springruntimeconfigrolloutjava.tests;

import com.github.xingjianluo.springruntimeconfigrolloutjava.config.RuntimeConfigService;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.ConfigType;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.model.SourceEntry;
import com.github.xingjianluo.springruntimeconfigrolloutjava.config.source.MapConfigSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 接口级集成测试：热更新发布、灰度判定、非法配置拒绝与版本回滚。
 */
@SpringBootTest(properties = "config.bootstrap.locations=")
@AutoConfigureMockMvc
class ConfigAdminIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(ConfigAdminIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RuntimeConfigService service;

    @BeforeEach
    void bootstrapRoot() throws Exception {
        // 每个测试前若未建立根版本则引导一个全量根版本。
        if (service.current().entries().isEmpty()) {
            Map<String, SourceEntry> data = new LinkedHashMap<>();
            data.put("feature.theme",
                    new SourceEntry("feature.theme", ConfigType.STRING, "light"));
            data.put("feature.timeoutMs",
                    new SourceEntry("feature.timeoutMs", ConfigType.INTEGER, "1000"));
            service.bootstrap(java.util.List.of(new MapConfigSource("it-base", 1, data)));
        }
    }

    @Test
    void fullPublishThenEvaluateReflectsHotUpdate() throws Exception {
        String currentVersion = service.current().version();
        String body = """
                {
                  "expectedVersion": "%s",
                  "note": "http full dark",
                  "items": [
                    {"key": "feature.theme", "type": "STRING", "value": "dark", "percentage": 100},
                    {"key": "feature.timeoutMs", "type": "INTEGER", "value": 2000, "percentage": 100}
                  ]
                }
                """.formatted(currentVersion);

        mockMvc.perform(post("/config/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").exists())
                .andExpect(jsonPath("$['entries']['feature.theme'].value").value("dark"));

        mockMvc.perform(get("/config/evaluate/feature.theme").param("subject", "u-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("dark"))
                .andExpect(jsonPath("$.hitRollout").value(true))
                .andExpect(jsonPath("$.percentage").value(100))
                .andExpect(jsonPath("$.version").exists())
                .andExpect(jsonPath("$.bucket").isNumber())
                .andExpect(jsonPath("$.reason").isNotEmpty());
        log.info("[HTTP] 全量发布后判定 feature.theme=dark，热更新无需重启");
    }

    @Test
    void canaryPublishSplitsSubjectsAndFallbackIsReported() throws Exception {
        String body = """
                {
                  "expectedVersion": "%s",
                  "note": "http canary 10",
                  "items": [
                    {"key": "feature.theme", "type": "STRING", "value": "dark",
                     "percentage": 10, "salt": "web-canary"},
                    {"key": "feature.timeoutMs", "type": "INTEGER", "value": 1000,
                     "percentage": 100}
                  ]
                }
                """.formatted(service.current().version());
        mockMvc.perform(post("/config/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        int hits = 0;
        int fallbacks = 0;
        for (int i = 0; i < 200; i++) {
            String value = mockMvc.perform(get("/config/evaluate/feature.theme")
                            .param("subject", "web-" + i))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()
                    .replaceAll("(?s).*\"value\":\"([^\"]+)\".*", "$1");
            if ("dark".equals(value)) {
                hits++;
            } else if ("light".equals(value)) {
                fallbacks++;
            }
        }
        log.info("[HTTP] 10% 灰度接口判定：命中新值 {} 人，回退旧值 {} 人", hits, fallbacks);
        org.junit.jupiter.api.Assertions.assertTrue(hits > 5 && hits < 40,
                "10% 灰度命中数量异常: " + hits);
        org.junit.jupiter.api.Assertions.assertEquals(200, hits + fallbacks);
    }

    @Test
    void invalidPublishesAreRejectedWithDistinctReasons() throws Exception {
        // 类型不兼容：STRING -> INTEGER
        String typeChange = """
                {
                  "expectedVersion": "%s",
                  "items": [
                    {"key": "feature.theme", "type": "INTEGER", "value": 1, "percentage": 100},
                    {"key": "feature.timeoutMs", "type": "INTEGER", "value": 1000, "percentage": 100}
                  ]
                }
                """.formatted(service.current().version());
        mockMvc.perform(post("/config/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(typeChange))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.rejected").value(true))
                .andExpect(jsonPath("$.reason").value("INCOMPATIBLE_TYPE_CHANGE"));

        // 值无法解析为声明类型
        String badValue = """
                {
                  "expectedVersion": "%s",
                  "items": [
                    {"key": "feature.theme", "type": "STRING", "value": "light", "percentage": 100},
                    {"key": "feature.timeoutMs", "type": "INTEGER", "value": "not-a-number", "percentage": 100}
                  ]
                }
                """.formatted(service.current().version());
        mockMvc.perform(post("/config/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(badValue))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("TYPE_MISMATCH"));

        // 过期版本号：乐观锁冲突
        String stale = """
                {
                  "expectedVersion": "v0-stale",
                  "items": [
                    {"key": "feature.theme", "type": "STRING", "value": "x", "percentage": 100},
                    {"key": "feature.timeoutMs", "type": "INTEGER", "value": 1, "percentage": 100}
                  ]
                }
                """;
        mockMvc.perform(post("/config/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(stale))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("VERSION_CONFLICT"));

        // 回滚不存在的版本
        mockMvc.perform(post("/config/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetVersion": "v404", "expectedVersion": null}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("UNKNOWN_TARGET_VERSION"));

        // 查询不存在的键
        mockMvc.perform(get("/config/evaluate/no.such.key").param("subject", "u1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("UNKNOWN_KEY"));

        log.info("[HTTP] 非法输入均被拒绝，原因码可区分：INCOMPATIBLE_TYPE_CHANGE / "
                + "TYPE_MISMATCH / VERSION_CONFLICT / UNKNOWN_TARGET_VERSION / UNKNOWN_KEY");
    }
}
