package com.sixbean.testpike.core.explore.domain.service;

import com.sixbean.testpike.common.clientobject.ExploreRunStatsUpdate;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.explore.api.dto.ExploreCreateInput;
import com.sixbean.testpike.core.explore.api.dto.ExploreSessionView;
import com.sixbean.testpike.core.explore.api.dto.ExploreStepInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


@SpringBootTest(classes = CoreApplication.class)
class ExploreSessionTest {

    @Autowired
    private ExploreService exploreService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    private long projectId;

    @BeforeEach
    void cleanAndAnchor() {
        // Fixture-scoped teardown (R37): only this test's own project subtree
        // is removed — a blanket TRUNCATE CASCADE on the shared dev database
        // wipes concurrent L3 probes' in-flight sessions/projects (r13 postmortem).
        SharedDbFixtureSweep.byProject(jdbcTemplate,
                SharedDbFixtureSweep.projectIdByCode(jdbcTemplate, "EXP-T209"));
        projectId = projectService.create(1L,
                new ProjectCreateInput("探索项目", "EXP-T209", "zh-CN", "P1", null));
        configCacheHolder.put("ai.explore.max-steps", "30");
        configCacheHolder.put("ai.explore.max-pages", "10");
    }

    private String snapshotValue(long sessionId, String key) {
        return jdbcTemplate.queryForObject(
                "SELECT budget_snapshot ->> ? FROM tp_explore_session WHERE id = ?",
                String.class, key, sessionId);
    }

    
    @Test
    void budgetSnapshotImmuneToHotConfigChange() {
        long id = exploreService.create(1L, projectId,
                new ExploreCreateInput("摸清登录流程", "http://localhost:5173"));
        assertEquals("30", snapshotValue(id, "maxSteps"), "创建快照应取 seed 值 30");
        assertEquals("3", snapshotValue(id, "noProgressLimit"), "无进展 N=3 常量入快照（备忘 G-7①）");

        configCacheHolder.put("ai.explore.max-steps", "7");
        try {
            assertEquals("30", snapshotValue(id, "maxSteps"), "运行中会话快照被热改侵蚀（IT-B9-02 违例）");
            long id2 = exploreService.create(1L, projectId,
                    new ExploreCreateInput("新会话", "http://localhost:5173/home"));
            assertEquals("7", snapshotValue(id2, "maxSteps"), "新会话应取热改后新值");
        } finally {
            configCacheHolder.put("ai.explore.max-steps", "30");
        }
    }

    
    @Test
    void statusFourValuesTransitionDomain() {
        long id = exploreService.create(1L, projectId,
                new ExploreCreateInput("目标", "http://localhost:5173"));
        ExploreSessionView running = exploreService.get(id);
        assertEquals(1, running.getStatus(), "新建默认 running");

        assertThrows(ServiceException.class, () -> exploreService.updateStatus(id, 9));
        assertThrows(ServiceException.class, () -> exploreService.updateStatus(id, 0));

        exploreService.updateStatus(id, 3); // budget-stop
        assertEquals(3, exploreService.get(id).getStatus());
        ServiceException after = assertThrows(ServiceException.class,
                () -> exploreService.updateStatus(id, 2), "终态后不得再转换");
        assertEquals(40902, after.getCode(), "终态封转应为状态机拒 40902，实际=" + after.getMessage());

        ServiceException missing = assertThrows(ServiceException.class,
                () -> exploreService.get(999999999L));
        assertEquals(40401, missing.getCode());
    }

    
    @Test
    void stepSeqUniqueConflictAndCounter() {
        long id = exploreService.create(1L, projectId,
                new ExploreCreateInput("目标", "http://localhost:5173"));
        ExploreStepInput first = new ExploreStepInput(1,
                "{\"action\":\"goto\",\"url\":\"http://localhost:5173\"}", "{\"ok\":true}",
                null, null, "先看首页", null);
        exploreService.recordStep(id, first);
        exploreService.recordStep(id, new ExploreStepInput(2,
                "{\"action\":\"click\",\"target\":\"登录\"}", "{\"ok\":true}",
                null, null, null, null));

        ServiceException dup = assertThrows(ServiceException.class,
                () -> exploreService.recordStep(id, first), "重复步序必须冲突");
        assertEquals(40904, dup.getCode(), "步序冲突应为 40904，实际=" + dup.getMessage());
        assertThrows(ServiceException.class, () -> exploreService.recordStep(id,
                new ExploreStepInput(0, "{}", "{}", null, null, null, null)), "seq<1 应拒");
        assertEquals(2, exploreService.get(id).getStepsUsed(), "steps_used=recordStep 单点累加");

        exploreService.updateStatus(id, 4);
        assertThrows(ServiceException.class, () -> exploreService.recordStep(id,
                new ExploreStepInput(3, "{}", "{}", null, null, null, null)), "终态会话禁步进");
    }

    
    @Test
    void updateStatsWritesRuntimeColumns() {
        long id = exploreService.create(1L, projectId,
                new ExploreCreateInput("目标", "http://localhost:5173"));
        exploreService.updateStats(id, new ExploreRunStatsUpdate(5, 120L, "# 阶段报告"));
        ExploreSessionView after = exploreService.get(id);
        assertEquals(5, after.getPagesVisited(), "pages_visited 覆盖写");
        assertEquals(120L, after.getCostCents(), "cost_cents 覆盖写");
        assertEquals("# 阶段报告", after.getReportMd(), "report_md 覆盖写");

        exploreService.updateStatus(id, 2);
        ServiceException blocked = assertThrows(ServiceException.class,
                () -> exploreService.updateStats(id, new ExploreRunStatsUpdate(9, 999L, null)),
                "终态会话禁 stats 回写");
        assertEquals(40902, blocked.getCode(), "终态守卫同 updateStatus，实际=" + blocked.getMessage());

        assertThrows(ServiceException.class, () -> exploreService.updateStats(999999999L,
                new ExploreRunStatsUpdate(1, 1L, null)), "会话不存在 40401");
    }

    
    @Test
    void createValidatesProjectAndFields() {
        assertThrows(ServiceException.class, () -> exploreService.create(1L, 999999999L,
                new ExploreCreateInput("目标", "http://localhost:5173")));
        assertThrows(ServiceException.class, () -> exploreService.create(1L, projectId,
                new ExploreCreateInput("  ", "http://localhost:5173")));
        assertThrows(ServiceException.class, () -> exploreService.create(1L, projectId,
                new ExploreCreateInput("目标", null)));
    }
}
