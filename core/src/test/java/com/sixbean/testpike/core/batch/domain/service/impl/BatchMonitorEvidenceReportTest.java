package com.sixbean.testpike.core.batch.domain.service.impl;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.domain.service.BatchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class BatchMonitorEvidenceReportTest {

    @Autowired
    private BatchService batchService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;
    private long targetId;

    @BeforeEach
    @AfterEach
    void cleanFixture() {
        jdbcTemplate.update("DELETE FROM tp_batch_report WHERE batch_id IN "
                + "(SELECT id FROM tp_exec_batch WHERE name LIKE 't274p4a%')");
        jdbcTemplate.update("DELETE FROM tp_monitor_episode WHERE target_id IN "
                + "(SELECT id FROM tp_monitor_target WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274p4a%'))");
        jdbcTemplate.update("DELETE FROM tp_monitor_target WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274p4a%')");
        jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE name LIKE 't274p4a%'");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274p4a%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE name LIKE 't274p4a%'");
    }

    @BeforeEach
    void seedTopology() {
        projectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code) VALUES ('t274p4a项目', substr(md5(random()::text), 1, 16)) "
                        + "RETURNING id", Long.class);
        envId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, 't274p4a环境', 1, 'http://127.0.0.1:1') RETURNING id", Long.class, projectId);
        targetId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_monitor_target (project_id, env_id, name, target_type) "
                        + "VALUES (?, ?, 't274p4a目标', 'HTTP') RETURNING id", Long.class, projectId, envId);
    }

    
    private long batchWithReport(Long env, String startedOffset, String finishedOffset) {
        Long batchId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_batch (project_id, env_id, batch_no, name, status, type, started_at, finished_at) "
                        + "VALUES (?, ?, ?, 't274p4a批', 6, 1, now() - interval '" + startedOffset + "', "
                        + (finishedOffset == null ? "NULL" : "now() - interval '" + finishedOffset + "'")
                        + ") RETURNING id",
                Long.class, projectId, env, "B-" + System.nanoTime());
        assert batchId != null;
        jdbcTemplate.update(
                "INSERT INTO tp_batch_report (batch_id, project_id, batch_status, total, pass_cnt, fail_cnt, "
                        + "skip_cnt, timeout_cnt, pass_rate, fail_dist_json, attr_json, duration_ms) "
                        + "VALUES (?, ?, 6, 4, 3, 1, 0, 0, 0.7500, '{}'::jsonb, '{}'::jsonb, 200000)",
                batchId, projectId);
        return batchId;
    }

    private void insertEpisode(long target, String startedOffset, String recoveredOffset, String cause) {
        jdbcTemplate.update(
                "INSERT INTO tp_monitor_episode (target_id, started_at, recovered_at, failure_count, cause_summary) "
                        + "VALUES (?, now() - interval '" + startedOffset + "', "
                        + (recoveredOffset == null ? "NULL" : "now() - interval '" + recoveredOffset + "'")
                        + ", 9, ?)", target, cause);
    }


    @Test
    void 批执行窗内红灯episode_报告附归因证据() {
        long batchId = batchWithReport(envId, "3 hours", "2 hours");
        insertEpisode(targetId, "2 hours 30 minutes", "1 hour 30 minutes", "登录链路 502");

        var report = batchService.report(batchId);
        assertEquals(1, report.monitorEvidence().size(), "episode [2h30m前~1h30m前] 与批窗 [3h前~2h前] 相交应命中");
        var evidence = report.monitorEvidence().get(0);
        assertEquals("t274p4a目标", evidence.targetName());
        assertEquals("登录链路 502", evidence.causeSummary());
        assertEquals(Boolean.FALSE, evidence.ongoing());
    }


    @Test
    void 窗口无红灯_证据空列表非null() {
        long batchId = batchWithReport(envId, "3 hours", "2 hours");
        var report = batchService.report(batchId);
        assertNotNull(report.monitorEvidence(), "无证据必须空列表非 null（消费侧免判空）");
        assertTrue(report.monitorEvidence().isEmpty());
    }


    @Test
    void 批次无环境_零证据不炸() {
        long batchId = batchWithReport(null, "3 hours", null);
        insertEpisode(targetId, "2 hours", null, "有红但批无环境不认");
        var report = batchService.report(batchId);
        assertTrue(report.monitorEvidence().isEmpty(), "env_id NULL=无环境归属，禁跨环境误挂（fail-closed）");
    }


    @Test
    void 异环境episode_不入本批证据() {
        Long otherEnv = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, 't274p4a他环境', 2, 'http://127.0.0.1:2') RETURNING id", Long.class, projectId);
        Long otherTarget = jdbcTemplate.queryForObject(
                "INSERT INTO tp_monitor_target (project_id, env_id, name, target_type) "
                        + "VALUES (?, ?, 't274p4a他目标', 'HTTP') RETURNING id", Long.class, projectId, otherEnv);
        long batchId = batchWithReport(envId, "3 hours", null);
        insertEpisode(otherTarget, "2 hours", null, "别的环境的红");
        var report = batchService.report(batchId);
        assertTrue(report.monitorEvidence().isEmpty(), "环境圈定禁跨环境误挂证据");
    }


    @Test
    void 读报告_快照行零变化() {
        long batchId = batchWithReport(envId, "3 hours", "2 hours");
        insertEpisode(targetId, "2 hours 30 minutes", "1 hour 30 minutes", "读时派生不回写");
        String before = jdbcTemplate.queryForObject(
                "SELECT md5(batch_id::text || total::text || pass_rate::text || coalesce(attr_json::text,'-')) "
                        + "FROM tp_batch_report WHERE batch_id = ?", String.class, batchId);
        batchService.report(batchId);
        String after = jdbcTemplate.queryForObject(
                "SELECT md5(batch_id::text || total::text || pass_rate::text || coalesce(attr_json::text,'-')) "
                        + "FROM tp_batch_report WHERE batch_id = ?", String.class, batchId);
        assertEquals(before, after, "归因证据=读时派生，快照行字段禁被读路径改写");
    }
}
