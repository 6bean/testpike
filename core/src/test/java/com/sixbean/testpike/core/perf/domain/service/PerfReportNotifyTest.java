package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.domain.component.PerfMetricFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfReportNotifyTest {

    @Autowired
    private PerfReportNotifyService perfReportNotifyService;

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfRunService perfRunService;

    @Autowired
    private PerfMetricService perfMetricService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;
    private long operatorId;
    private long ownerId;

    @BeforeEach
    void seedFixtures() {
        cleanupRows();
        long uniq = System.nanoTime() % 1_000_000_000L;
        operatorId = seedUser("perfnop_" + uniq);
        ownerId = seedUser("perfnpo_" + uniq);
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status, test_owner_id, created_by) "
                + "VALUES (?, ?, 1, ?, ?)", "发卡片测试项目_" + uniq, "PERFCARD" + uniq, ownerId, operatorId);
        projectId = lastId("tp_project");
        jdbcTemplate.update("INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                + "VALUES (?, '性能-发卡片测', 1, 'http://perf-target.local/api')", projectId);
        envId = lastId("tp_project_env");
        jdbcTemplate.update("INSERT INTO tp_perf_whitelist (project_id, env_id, max_concurrency, allowed_modes) VALUES (?, ?, 1000, '[]'::jsonb)", projectId, envId);
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
    }

    private void cleanupRows() {
        jdbcTemplate.update("DELETE FROM tp_notification WHERE dedup_key LIKE 'PERF_REPORT_CARD:%'");
        jdbcTemplate.update("DELETE FROM tp_perf_sample WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFCARD%')");
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFCARD%')");
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFCARD%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFCARD%')");
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFCARD%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFCARD%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PERFCARD%'");
        jdbcTemplate.update("DELETE FROM tp_user WHERE username LIKE 'perfnop_%' OR username LIKE 'perfnpo_%'");
    }

    private long seedUser(String username) {
        jdbcTemplate.update(
                "INSERT INTO tp_user (username, password_hash, display_name, role, status) "
                        + "VALUES (?, 'x', ?, 1, 1)", username, "卡片用户" + username);
        return lastId("tp_user");
    }

    private long lastId(String table) {
        return jdbcTemplate.queryForObject("SELECT id FROM " + table + " ORDER BY id DESC LIMIT 1", Long.class);
    }

    
    private long launchRunWithFrames(Map<String, Double> sla) {
        long scenarioId = perfScenarioService.create(projectId, new PerfScenarioInput(
                "发卡片场景_" + System.nanoTime(), envId, "CONSTANT_RPS",
                Map.of("targetRps", 50, "durationSeconds", 600), null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")), sla));
        long runId = perfRunService.launch(projectId, scenarioId, operatorId);
        perfMetricService.recordFrames(projectId, runId, List.of(
                new PerfMetricFrame(0, 40.0, 120.0, 0.02, null, null, null, 35.0, 4.0),
                new PerfMetricFrame(1, 60.0, 200.0, 0.08, null, null, null, null, null)));
        return runId;
    }

    private List<Map<String, Object>> cardsOf(long runId) {
        return jdbcTemplate.queryForList(
                "SELECT user_id, event_type, title, content, dedup_key FROM tp_notification "
                        + "WHERE dedup_key LIKE ? ORDER BY user_id", "PERF_REPORT_CARD:" + runId + ":%");
    }

    @Test
    void sendsReadableCardToOperatorAndOwnerDeduplicated() {
        long runId = launchRunWithFrames(Map.of("p95Ms", 150.0));
        int sent = perfReportNotifyService.sendCard(projectId, runId);

        assertThat(sent).isEqualTo(2);
        List<Map<String, Object>> cards = cardsOf(runId);
        assertThat(cards).hasSize(2);
        assertThat(cards).extracting(r -> ((Number) r.get("user_id")).longValue())
                .containsExactlyInAnyOrder(operatorId, ownerId);
        assertThat(cards).allSatisfy(card -> {
            assertThat((String) card.get("event_type")).isEqualTo("PERF_REPORT_CARD");
            String title = (String) card.get("title");
            String content = (String) card.get("content");
            assertThat(title).contains("压测运行 #" + runId).contains("未通过");
            assertThat(content).contains("压测响应时间 P95").contains("超阈值 150ms");
            assertThat(content).contains("峰值秒 P95").contains("平均 RPS").contains("错误率");
            assertThat(content).contains("认证");
            assertThat(content).doesNotContain("{").doesNotContain("}");
        });
    }

    @Test
    void passedRunCardStatesConclusionHonestly() {
        long runId = launchRunWithFrames(Map.of("p95Ms", 500.0));
        perfReportNotifyService.sendCard(projectId, runId);
        List<Map<String, Object>> cards = cardsOf(runId);
        assertThat(cards).hasSize(2);
        assertThat((String) cards.get(0).get("title")).contains("通过");
        assertThat((String) cards.get(0).get("title")).doesNotContain("未通过");
    }

    @Test
    void resendIsDeduplicatedNoStorm() {
        long runId = launchRunWithFrames(null);
        perfReportNotifyService.sendCard(projectId, runId);
        perfReportNotifyService.sendCard(projectId, runId);
        assertThat(cardsOf(runId)).hasSize(2);
    }

    @Test
    void crossProjectOrMissingRunRejectedWithoutLeakage() {
        long runId = launchRunWithFrames(null);
        long otherCode = System.nanoTime() % 1_000_000_000L;
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES ('他项目', ?, 1)",
                "PERFCARDX" + otherCode);
        long otherProject = lastId("tp_project");
        assertThatThrownBy(() -> perfReportNotifyService.sendCard(otherProject, runId))
                .isInstanceOf(ServiceException.class).hasMessageContaining("不存在");
        assertThatThrownBy(() -> perfReportNotifyService.sendCard(projectId, runId + 999_999L))
                .isInstanceOf(ServiceException.class).hasMessageContaining("不存在");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PERFCARDX%'");
    }
}
