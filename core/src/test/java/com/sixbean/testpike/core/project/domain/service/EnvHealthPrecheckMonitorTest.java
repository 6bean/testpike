package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class EnvHealthPrecheckMonitorTest {

    @Autowired
    private EnvHealthService envHealthService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;
    private long otherEnvId;
    private long targetId;
    private long otherTargetId;

    @BeforeEach
    @AfterEach
    void cleanFixture() {
        jdbcTemplate.update("DELETE FROM tp_monitor_episode WHERE target_id IN "
                + "(SELECT id FROM tp_monitor_target WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274p4b%'))");
        jdbcTemplate.update("DELETE FROM tp_monitor_check_log WHERE target_id IN "
                + "(SELECT id FROM tp_monitor_target WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274p4b%'))");
        jdbcTemplate.update("DELETE FROM tp_monitor_target WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274p4b%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274p4b%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE name LIKE 't274p4b%'");
    }

    @BeforeEach
    void seedTopology() {
        projectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code) VALUES ('t274p4b项目', substr(md5(random()::text), 1, 16)) "
                        + "RETURNING id", Long.class);
        envId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, 't274p4b环境', 1, 'http://127.0.0.1:1') RETURNING id", Long.class, projectId);
        otherEnvId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, 't274p4b他环境', 2, 'http://127.0.0.1:2') RETURNING id", Long.class, projectId);
        targetId = insertTarget(envId, "t274p4b登录链路");
        otherTargetId = insertTarget(otherEnvId, "t274p4b他目标");
    }

    private long insertTarget(long env, String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_monitor_target (project_id, env_id, name, target_type) "
                        + "VALUES (?, ?, ?, 'LOGIN') RETURNING id", Long.class, projectId, env, name);
    }

    private void insertEpisode(long target, String startedOffset, String recoveredOffset, String cause) {
        jdbcTemplate.update(
                "INSERT INTO tp_monitor_episode (target_id, started_at, recovered_at, failure_count, cause_summary) "
                        + "VALUES (?, now() - interval '" + startedOffset + "', "
                        + (recoveredOffset == null ? "NULL" : "now() - interval '" + recoveredOffset + "'")
                        + ", 9, ?)", target, cause);
    }

    
    private void markEnvUnhealthy(String healthMsg) {
        jdbcTemplate.update("UPDATE tp_project_env SET health_status = 2, "
                + "health_checked_at = now(), health_msg = ? WHERE id = ?", healthMsg, envId);
    }


    @Test
    void 环境近窗红且监控进行中_预检文案尾引已红分钟数与现象() {
        markEnvUnhealthy("SSO 登录 502");
        insertEpisode(targetId, "38 minutes 30 seconds", null, "登录探测连续 9 次失败");
        String warning = envHealthService.envHealthWarning(envId);
        assertNotNull(warning, "近窗红必须出预检警告（既有语义）");
        assertTrue(warning.contains("SSO 登录 502"), "原健康异常文案保留，实际=" + warning);
        assertTrue(warning.contains("监控显示该链路已红 38 分钟"),
                "进行中 episode 尾引「已红 N 分钟」，实际=" + warning);
        assertTrue(warning.contains("登录探测连续 9 次失败"),
                "引用现象摘要 causeSummary，实际=" + warning);
    }


    @Test
    void 监控episode已恢复_预检文案不引用() {
        markEnvUnhealthy("SSO 登录 502");
        insertEpisode(targetId, "3 hours", "2 hours", "旧故障已恢复");
        String warning = envHealthService.envHealthWarning(envId);
        assertNotNull(warning);
        assertTrue(warning.contains("SSO 登录 502"));
        assertTrue(!warning.contains("监控显示"),
                "禁引用已恢复事件（红=当前事实），实际=" + warning);
    }


    @Test
    void 环境健康不红_预检不警告即使监控进行中() {
        insertEpisode(targetId, "42 minutes", null, "登录链路持续 DOWN");
        assertNull(envHealthService.envHealthWarning(envId),
                "预检「未知不拦」语义不因监控联动改变——健康侧无红=无警告");
    }


    @Test
    void 异环境进行中episode_不引用() {
        markEnvUnhealthy("数据库连接超时");
        insertEpisode(otherTargetId, "50 minutes", null, "别的环境的红");
        String warning = envHealthService.envHealthWarning(envId);
        assertNotNull(warning);
        assertTrue(!warning.contains("别的环境的红"),
                "联动证据必须按 env_id 圈定，实际=" + warning);
    }
}
