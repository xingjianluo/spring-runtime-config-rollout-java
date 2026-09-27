package com.github.xingjianluo.springruntimeconfigrolloutjava.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** 端到端：热更新、灰度查询、可区分拒绝原因与回退。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConfigApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConfigApiIntegrationTest.class);
    private static final String KEY = "feature.recommend.strategy";

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private JsonNode post(String body) {
        ResponseEntity<String> resp = rest.postForEntity(url("/api/admin/config/update"),
                new HttpEntity<>(body, jsonHeaders()), String.class);
        try {
            return mapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void fullFlow_updateQueryRejectRollback() {
        // 1. 合法热更新：50% 灰度
        String validBody = """
                {"source":"database","entries":[
                  {"key":"%s","value":"fresh","rolloutPercent":50},
                  {"key":"feature.search.page-size","value":"20","rolloutPercent":100}
                ]}""".formatted(KEY);
        JsonNode ok = post(validBody);
        assertThat(ok.path("version").asLong()).isGreaterThanOrEqualTo(2);
        long vAfterUpdate = ok.path("version").asLong();
        log.info("[集成] 热更新成功: version={}, 50% 灰度 fresh", vAfterUpdate);

        // 2. 灰度判定：枚举找到一个未命中的主体，应回退缺省值 stable
        String unhit = null;
        String hit = null;
        for (int i = 0; i < 500; i++) {
            String subject = "u" + i;
            ResponseEntity<String> resp = rest.getForEntity(
                    url("/api/config?key=" + KEY + "&subject=" + subject), String.class);
            JsonNode node = readBody(resp);
            if (node.path("cohortHit").asBoolean() && node.path("value").asText().equals("fresh")) {
                hit = subject;
            }
            if (node.path("source").asText().equals("SCHEMA_DEFAULT")) {
                unhit = subject;
            }
            if (hit != null && unhit != null) {
                break;
            }
        }
        assertThat(hit).as("应能找到灰度命中主体").isNotNull();
        assertThat(unhit).as("应能找到未命中主体").isNotNull();

        ResponseEntity<String> hitResp = rest.getForEntity(
                url("/api/config?key=" + KEY + "&subject=" + hit), String.class);
        JsonNode hitNode = readBody(hitResp);
        assertThat(hitNode.path("value").asText()).isEqualTo("fresh");
        assertThat(hitNode.path("rolloutPercent").asInt()).isEqualTo(50);
        assertThat(hitNode.path("basis").asText()).contains("GRAY");
        log.info("[集成] 灰度命中: subject={}, version={}, cohort=GRAY, bucket={}, basis={}",
                hit, hitNode.path("version").asLong(), hitNode.path("bucket").asInt(),
                hitNode.path("basis").asText());
        log.info("[集成] 灰度未命中回退缺省: subject={}, value=stable, cohort=FULL, basis=SCHEMA_DEFAULT",
                unhit);

        // 3. 非法灰度比例 -> 422 + 可区分原因码
        ResponseEntity<String> badPercent = rest.postForEntity(
                url("/api/admin/config/update"),
                new HttpEntity<>("""
                        {"source":"database","entries":[
                          {"key":"%s","value":"fresh","rolloutPercent":150}
                        ]}""".formatted(KEY), jsonHeaders()), String.class);
        assertThat(badPercent.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        JsonNode badNode = readBody(badPercent);
        assertThat(badNode.path("reason").asText()).isEqualTo("INVALID_ROLLOUT_PERCENT");
        assertThat(badNode.path("rejected").asBoolean()).isTrue();
        log.info("[集成] 非法比例被拒绝: http=422, reason=INVALID_ROLLOUT_PERCENT, detail={}",
                badNode.path("detail").asText());

        // 4. 非法类型 -> INVALID_VALUE_TYPE
        JsonNode badType = post("""
                {"source":"database","entries":[
                  {"key":"feature.checkout.enabled","value":"maybe","rolloutPercent":100}
                ]}""");
        assertThat(badType.path("reason").asText()).isEqualTo("INVALID_VALUE_TYPE");
        log.info("[集成] 非法类型被拒绝: reason=INVALID_VALUE_TYPE");

        // 5. 未知来源 -> UNKNOWN_SOURCE
        ResponseEntity<String> badSource = rest.postForEntity(
                url("/api/admin/config/update"),
                new HttpEntity<>("""
                        {"source":"zzz","entries":[]}""", jsonHeaders()), String.class);
        assertThat(badSource.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(readBody(badSource).path("reason").asText()).isEqualTo("UNKNOWN_SOURCE");
        log.info("[集成] 未知来源被拒绝: http=404, reason=UNKNOWN_SOURCE");

        // 6. 不可解析 JSON -> 400 ILLEGAL_PAYLOAD
        ResponseEntity<String> malformed = rest.postForEntity(
                url("/api/admin/config/update"),
                new HttpEntity<>("{not-json", jsonHeaders()), String.class);
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(readBody(malformed).path("reason").asText()).isEqualTo("ILLEGAL_PAYLOAD");
        log.info("[集成] 非法JSON被拒绝: http=400, reason=ILLEGAL_PAYLOAD");

        // 7. 旧值仍然生效（非法提交没有部分生效）
        JsonNode still = readBody(rest.getForEntity(
                url("/api/config?key=" + KEY + "&subject=" + hit), String.class));
        assertThat(still.path("value").asText()).isEqualTo("fresh");
        assertThat(still.path("version").asLong()).isEqualTo(vAfterUpdate);
        log.info("[集成] 拒绝后旧版本继续生效: version={} 未变化", vAfterUpdate);

        // 8. 再发布一个全量版本，然后把 database 回退到此前 50% 灰度版本（vAfterUpdate）。
        //    回退是一次重新发布，内容取自历史快照但产生新版本号。
        String fullBody = """
                {"source":"database","entries":[
                  {"key":"%s","value":"latest","rolloutPercent":100},
                  {"key":"feature.search.page-size","value":"20","rolloutPercent":100}
                ]}""".formatted(KEY);
        long vFull = post(fullBody).path("version").asLong();
        assertThat(rest.getForEntity(url("/api/config?key=" + KEY + "&subject=" + hit), String.class)
                .getBody()).contains("latest");

        ResponseEntity<String> rollbackResp = rest.postForEntity(
                url("/api/admin/config/rollback"),
                new HttpEntity<>("""
                        {"source":"database","targetVersion":%d}""".formatted(vAfterUpdate),
                        jsonHeaders()), String.class);
        assertThat(rollbackResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rolled = readBody(rollbackResp);
        assertThat(rolled.path("rollbackOf").asLong()).isEqualTo(vAfterUpdate);
        assertThat(rolled.path("version").asLong()).isGreaterThan(vFull);

        // 9. 回退到"会导致必填键缺失"的初始版本必须被拒绝（回滚也要校验，不产生脏状态）
        ResponseEntity<String> badRollback = rest.postForEntity(
                url("/api/admin/config/rollback"),
                new HttpEntity<>("""
                        {"source":"database","targetVersion":1}""", jsonHeaders()), String.class);
        assertThat(badRollback.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(readBody(badRollback).path("reason").asText())
                .isEqualTo("MISSING_REQUIRED_DEFAULT");

        // 回退后判定结论与灰度版本一致
        JsonNode afterRollback = readBody(rest.getForEntity(
                url("/api/config?key=" + KEY + "&subject=" + hit), String.class));
        assertThat(afterRollback.path("value").asText()).isEqualTo("fresh");
        assertThat(afterRollback.path("rolloutPercent").asInt()).isEqualTo(50);
        log.info("[集成] 回退成功: rollbackOf={}, 新版本 version={}; 回退到非法旧状态被拒绝",
                vAfterUpdate, rolled.path("version").asLong());
    }

    private JsonNode readBody(ResponseEntity<String> resp) {
        try {
            return mapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
