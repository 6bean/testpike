package com.sixbean.testpike.core.monitor.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.monitor.api.dto.MonitorEpisodeView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class MonitorLinkageEvidenceTest {

    @Autowired
    private MonitorEpisodeService monitorEpisodeService;

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
                + "(SELECT id FROM tp_project WHERE name LIKE 't274p4%'))");
        jdbcTemplate.update("DELETE FROM tp_monitor_check_log WHERE target_id IN "
                + "(SELECT id FROM tp_monitor_target WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274p4%'))");
        jdbcTemplate.update("DELETE FROM tp_monitor_target WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274p4%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274p4%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE name LIKE 't274p4%'");
    }

    @BeforeEach
    void seedTopology() {
        projectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code) VALUES ('t274p4项目', substr(md5(random()::text), 1, 16)) "
                        + "RETURNING id", Long.class);
        envId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, 't274p4环境', 1, 'http://127.0.0.1:1') RETURNING id", Long.class, projectId);
        otherEnvId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, 't274p4他环境', 2, 'http://127.0.0.1:2') RETURNING id", Long.class, projectId);
        targetId = insertTarget(envId, "t274p4目标");
        otherTargetId = insertTarget(otherEnvId, "t274p4他目标");
    }

    private long insertTarget(long env, String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_monitor_target (project_id, env_id, name, target_type) "
                        + "VALUES (?, ?, ?, 'HTTP') RETURNING id", Long.class, projectId, env, name);
    }

    private long insertEpisode(long target, String startedOffset, String recoveredOffset, String cause) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_monitor_episode (target_id, started_at, recovered_at, failure_count, cause_summary) "
                        + "VALUES (?, now() - interval '" + startedOffset + "', "
                        + (recoveredOffset == null ? "NULL" : "now() - interval '" + recoveredOffset + "'")
                        + ", 9, ?) RETURNING id", Long.class, target, cause);
    }


    @Test
    void 失败窗落在episode持续期内_归因证据命中() {
        insertEpisode(targetId, "3 hours", "2 hours", "SSO 回调 502");
        List<MonitorEpisodeView> hits = monitorEpisodeService.findEpisodesOverlappingWindow(
                envId, java.time.LocalDateTime.now().minusMinutes(150),
                java.time.LocalDateTime.now().minusMinutes(140));
        assertEquals(1, hits.size(), "失败窗与 episode [started, recovered] 交集非空应命中");
        MonitorEpisodeView v = hits.get(0);
        assertEquals("t274p4目标", v.targetName());
        assertEquals("HTTP", v.targetType());
        assertEquals("SSO 回调 502", v.causeSummary());
        assertEquals(Boolean.FALSE, v.ongoing(), "已恢复 episode ongoing=false");
    }

    @Test
    void 失败窗完全在episode恢复后_不命中() {
        insertEpisode(targetId, "3 hours", "2 hours", "已恢复的旧故障");
        List<MonitorEpisodeView> hits = monitorEpisodeService.findEpisodesOverlappingWindow(
                envId, java.time.LocalDateTime.now().minusHours(1), java.time.LocalDateTime.now());
        assertTrue(hits.isEmpty(), "episode 窗口与失败窗无交集必须不命中（禁误归因）");
    }


    @Test
    void 进行中episode_失败窗尾端为当前时刻_命中() {
        insertEpisode(targetId, "42 minutes", null, "登录链路持续 DOWN");
        List<MonitorEpisodeView> hits = monitorEpisodeService.findEpisodesOverlappingWindow(
                envId, java.time.LocalDateTime.now().minusHours(1), java.time.LocalDateTime.now());
        assertEquals(1, hits.size());
        assertEquals(Boolean.TRUE, hits.get(0).ongoing(), "recovered_at NULL=进行中 ongoing=true");
    }


    @Test
    void 异环境episode_不混入本环境证据() {
        insertEpisode(otherTargetId, "2 hours", null, "别的环境的红");
        List<MonitorEpisodeView> hits = monitorEpisodeService.findEpisodesOverlappingWindow(
                envId, java.time.LocalDateTime.now().minusHours(1), java.time.LocalDateTime.now());
        assertTrue(hits.isEmpty(), "联动证据必须按 env_id 圈定，禁跨环境误挂");
    }


    @Test
    void 环境当前红_取进行中episode供横幅与预检引用() {
        insertEpisode(targetId, "12 minutes", null, "环境 ERP-测试 已红");
        MonitorEpisodeView open = monitorEpisodeService.findOpenEpisodeByEnv(envId);
        assertNotNull(open, "进行中 episode 必须可只读查得（禁触发再探测）");
        assertEquals("环境 ERP-测试 已红", open.causeSummary());
        assertTrue(open.ongoing());
        assertNotNull(open.startedAt());
    }

    @Test
    void 环境无进行中episode_返回空() {
        insertEpisode(targetId, "3 hours", "2 hours", "旧故障");
        assertNull(monitorEpisodeService.findOpenEpisodeByEnv(envId),
                "仅有已恢复 episode 时横幅/预检不得引用（红=当前事实）");
    }


    @Test
    void 多条episode命中_按开始时间倒序() {
        insertEpisode(targetId, "5 hours", "4 hours", "旧红");
        insertEpisode(targetId, "2 hours", "1 hours", "新红");
        List<MonitorEpisodeView> hits = monitorEpisodeService.findEpisodesOverlappingWindow(
                envId, java.time.LocalDateTime.now().minusHours(6), java.time.LocalDateTime.now());
        assertEquals(2, hits.size());
        assertEquals("新红", hits.get(0).causeSummary(), "结论行优先展示最近 episode");
    }
}
