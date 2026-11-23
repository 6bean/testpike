package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoUpgradeFlakyLinkView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoUpgradeFlakyLinkServiceTest {

    private static final int S = 3;
    private static final int F = 4;

    @Autowired
    private AppautoUpgradeFlakyLinkService flakyLinkService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long executorId;
    private long projectId;
    private long planId;

    @BeforeEach
    void setUp() {
        String code = "flk" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "升级flaky测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        String execName = "flk-exec-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_executor (name) VALUES (?)", execName);
        executorId = jdbcTemplate.queryForObject("SELECT id FROM tp_executor WHERE name = ?",
                Long.class, execName);
        jdbcTemplate.update("INSERT INTO tp_upgrade_plan (scope, status, operator) "
                + "VALUES ('node', 'RUNNING', 'flk-test')");
        planId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_upgrade_plan WHERE operator = 'flk-test'", Long.class);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tp_upgrade_plan_step WHERE plan_id = ?", planId);
        jdbcTemplate.update("DELETE FROM tp_upgrade_plan WHERE id = ?", planId);
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_executor WHERE id = ?", executorId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    
    private void seedUpgradeStep(int hoursAgo) {
        jdbcTemplate.update("""
                INSERT INTO tp_upgrade_plan_step (plan_id, node_id, kind, phase, status,
                                                  target_version, updated_at)
                VALUES (?, ?, 'node', 'batch', 'SUCCESS', '2.0.0',
                        CURRENT_TIMESTAMP - (? || ' hours')::interval)
                """, planId, String.valueOf(executorId), String.valueOf(hoursAgo));
    }

    
    private void seedRun(int hoursAgo, int status) {
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, executor_id, status, task_type,
                                          finished_at)
                VALUES (?, 990001, ?, ?, 6, CURRENT_TIMESTAMP - (? || ' hours')::interval)
                """, projectId, executorId, status, String.valueOf(hoursAgo));
    }

    @Test
    void sharpDropAfterUpgradeLinksEventWithHumanWords() {
        seedUpgradeStep(12);
        for (int i = 0; i < 4; i++) {
            seedRun(15, S);
        }
        seedRun(6, S);
        seedRun(6, F);
        seedRun(6, F);
        seedRun(6, F);

        List<AppautoUpgradeFlakyLinkView> links = flakyLinkService.correlate();
        assertEquals(1, links.size(), "骤降必须出关联行：\n" + links);
        AppautoUpgradeFlakyLinkView link = links.get(0);
        assertEquals(executorId, link.executorId());
        assertEquals(1.0, link.beforePassRate(), 1e-6);
        assertEquals(0.25, link.afterPassRate(), 1e-6);
        assertEquals("2.0.0", link.targetVersion());
        assertTrue(link.advice().contains("疑似升级引入"),
                "人话建议须带归因先验：" + link.advice());
    }

    @Test
    void healthyNodeProducesNoLink() {
        seedUpgradeStep(12);
        for (int i = 0; i < 3; i++) {
            seedRun(15, S);
            seedRun(6, S);
        }
        assertTrue(flakyLinkService.correlate().isEmpty(), "通过率不降=诚实零关联");
    }

    @Test
    void noUpgradeStepsEmptyList() {
        assertEquals(List.of(), flakyLinkService.correlate());
    }
}
