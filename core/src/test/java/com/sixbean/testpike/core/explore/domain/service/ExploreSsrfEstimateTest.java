package com.sixbean.testpike.core.explore.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.explore.api.dto.ExploreCreateInput;
import com.sixbean.testpike.core.explore.api.dto.ExploreEstimate;
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
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ExploreSsrfEstimateTest {

    @Autowired
    private ExploreService exploreService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void cleanAndAnchor() {
        // Fixture-scoped teardown (R37): only the EXP-SSRF project subtree.
        SharedDbFixtureSweep.byProject(jdbcTemplate,
                SharedDbFixtureSweep.projectIdByCode(jdbcTemplate, "EXP-SSRF"));
        projectId = projectService.create(1L,
                new ProjectCreateInput("SSRF项目", "EXP-SSRF", "zh-CN", "P1", null));
    }

    private long sessionRows() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_explore_session WHERE project_id = ?",
                Long.class, projectId);
        return n == null ? 0L : n;
    }

    
    @Test
    void createRejectsSsrfUrls() {
        ServiceException file = assertThrows(ServiceException.class, () -> exploreService.create(1L,
                projectId, new ExploreCreateInput("目标", "file:///etc/passwd")), "file:// 必拒");
        assertEquals(40001, file.getCode());
        assertTrue(file.getMessage().contains("起始") || file.getMessage().contains("start"),
                "人话须点名起始 URL，实际=" + file.getMessage());

        ServiceException meta = assertThrows(ServiceException.class, () -> exploreService.create(1L,
                projectId, new ExploreCreateInput("目标", "http://169.254.169.254/latest/meta-data/")));
        assertTrue(meta.getMessage().contains("169.254") || meta.getMessage().contains("元数据"),
                "元数据端点人话点名字，实际=" + meta.getMessage());

        assertThrows(ServiceException.class, () -> exploreService.create(1L, projectId,
                new ExploreCreateInput("目标", "http://fd00:ec2::254/mcp")));
        assertThrows(ServiceException.class, () -> exploreService.create(1L, projectId,
                new ExploreCreateInput("目标", "127.0.0.1:5173")));
        assertEquals(0, sessionRows(), "SSRF 拒集必须零落库");
    }

    
    @Test
    void createAllowsLocalhostFixture() {
        long id = exploreService.create(1L, projectId,
                new ExploreCreateInput("摸清首页", "http://127.0.0.1:5173/"));
        assertTrue(id > 0);
        assertEquals(1, sessionRows());
    }

    
    @Test
    void createRejectsGoalOver2000Chars() {
        String over = "字".repeat(2001);
        assertThrows(ServiceException.class, () -> exploreService.create(1L, projectId,
                new ExploreCreateInput(over, "http://localhost:5173")));
        long id = exploreService.create(1L, projectId,
                new ExploreCreateInput("字".repeat(2000), "http://localhost:5173"));
        assertTrue(id > 0, "恰 2000 字为合法边界");
    }

    
    @Test
    void estimateIsPureComputation() {
        long before = sessionRows();
        ExploreEstimate est = exploreService.estimate(projectId,
                new ExploreCreateInput("验证列表页搜索返回", "http://localhost:5173"));
        assertTrue(est.estimatedTokens() > 0, "token 预估应为正");
        assertTrue(est.estimatedCostCents() >= 0, "成本（分）不可为负");
        assertEquals(30, est.maxSteps(), "快照口径=max-steps 现值（seed 30）");
        assertEquals(before, sessionRows(), "预估链路禁落库（TC-B9-01）");
    }

    
    @Test
    void estimateValidatesBeforeComputing() {
        assertThrows(ServiceException.class, () -> exploreService.estimate(projectId,
                new ExploreCreateInput("目标", "http://169.254.169.254/")));
        assertThrows(ServiceException.class, () -> exploreService.estimate(projectId,
                new ExploreCreateInput("", "http://localhost:5173")));
        assertThrows(ServiceException.class, () -> exploreService.estimate(999999999L,
                new ExploreCreateInput("目标", "http://localhost:5173")));
    }
}
