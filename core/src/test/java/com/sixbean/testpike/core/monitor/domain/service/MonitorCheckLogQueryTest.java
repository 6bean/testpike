package com.sixbean.testpike.core.monitor.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.monitor.api.dto.MonitorCheckLogView;
import com.sixbean.testpike.core.monitor.api.dto.MonitorTargetInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class MonitorCheckLogQueryTest {

    @Autowired
    private MonitorTargetService monitorTargetService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;
    private long targetId;
    private String tag;

    @BeforeEach
    void prepareFixture() {
        tag = "t274clq" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                tag, tag.substring(tag.length() - 8).toUpperCase());
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project WHERE name = ?", Long.class, tag);
        jdbcTemplate.update("INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                + "VALUES (?, '主环境', 1, 'http://127.0.0.1:1/')", projectId);
        envId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project_env WHERE project_id = ? LIMIT 1", Long.class, projectId);
        targetId = monitorTargetService.create(projectId, new MonitorTargetInput(
                "查询口目标", envId, "HTTP", Map.of("url", "http://127.0.0.1:1/health"), 60, true));
    }

    @AfterEach
    void cleanFixture() {
        if (tag == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM tp_monitor_check_log WHERE target_id IN "
                + "(SELECT id FROM tp_monitor_target WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_monitor_episode WHERE target_id IN "
                + "(SELECT id FROM tp_monitor_target WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_monitor_target WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    
    private void insertLogs(int n, String outcome) {
        for (int i = 1; i <= n; i++) {
            jdbcTemplate.update("INSERT INTO tp_monitor_check_log "
                            + "(target_id, checked_at, outcome, latency_ms, detail) "
                            + "VALUES (?, now() - make_interval(mins => ?), ?, ?, ?)",
                    targetId, n - i, outcome, i, "第" + i + "轮");
        }
    }

    @Test
    void recentCheckLogs返回倒序且字段完整() {
        insertLogs(3, "ok");
        jdbcTemplate.update("INSERT INTO tp_monitor_check_log "
                + "(target_id, checked_at, outcome, detail) VALUES (?, now() - interval '2 hour', 'fail', '连接拒绝')",
                targetId);
        List<MonitorCheckLogView> logs = monitorTargetService.recentCheckLogs(targetId, 10);
        assertEquals(4, logs.size());
        assertEquals("ok", logs.get(0).outcome());
        assertEquals(Integer.valueOf(3), logs.get(0).latencyMs());
        assertEquals("fail", logs.get(3).outcome());
        assertEquals("连接拒绝", logs.get(3).detail());
        assertNotNull(logs.get(0).checkedAt());
        assertTrue(logs.get(0).checkedAt().isAfter(logs.get(3).checkedAt()),
                "checked_at 必须倒序（最近优先）: " + logs);
    }

    @Test
    void deferred行也进趋势不过滤失真() {
        insertLogs(1, "deferred");
        List<MonitorCheckLogView> logs = monitorTargetService.recentCheckLogs(targetId, 10);
        assertEquals(1, logs.size());
        assertEquals("deferred", logs.get(0).outcome());
    }

    @Test
    void limit硬钳200防拉穿保留策略() {
        insertLogs(205, "ok");
        assertEquals(200, monitorTargetService.recentCheckLogs(targetId, 1000).size(),
                "超限请求必须钳到 200（探测频率下限同级红线：查询口禁拉穿 retention）");
        assertEquals(200, monitorTargetService.recentCheckLogs(targetId, 0).size());
    }

    @Test
    void 目标不存在人话404拒() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> monitorTargetService.recentCheckLogs(999999999L, 10));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        assertTrue(ex.getMessage().contains("监控目标不存在"), "人话文案: " + ex.getMessage());
    }
}
