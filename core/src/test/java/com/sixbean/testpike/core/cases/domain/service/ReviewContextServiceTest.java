package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseReviewLogInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.domain.component.ReviewAction;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ReviewContextServiceTest {

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long reviewerId;

    /** Fixture projects of this suite; the teardown sweeps only these rows. */
    private final java.util.List<Long> reviewContextTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM tp_user_role WHERE user_id IN (SELECT id FROM tp_user WHERE username = 'o8reviewer')");
        jdbcTemplate.update("DELETE FROM tp_user WHERE username = 'o8reviewer'");
        // Shared dev database (R37): no blanket table wipe — the FK-tree sweep
        // reaches this suite's cases, steps, tag links, review logs and audit
        // rows through the tracked fixture projects only.
        ProjectFixtureRig.sweep(jdbcTemplate, reviewContextTrackedProjects);
        reviewContextTrackedProjects.clear();
        reviewerId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, display_name, role, status)
                VALUES ('o8reviewer', '评审员O8', 2, 1) RETURNING id
                """, Long.class);
    }

    private long createProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                reviewContextTrackedProjects, "项目" + code, code);
    }

    private long createCase(long projectId, String title) {
        return caseService.create(1L, new CaseCreateInput(projectId, title, "前置", "预期",
                2, 2, "web", 1, 1, 1, null,
                List.of(new CaseStepInput(1, "步骤1", 0)))).id();
    }

    private void review(long caseId, int version, ReviewAction action, String remark) {
        caseService.review(reviewerId, caseId, action, remark, version);
    }

 
    private void review(long caseId, int version, ReviewAction action, String remark,
                        List<String> feedbackTypes) {
        caseService.review(reviewerId, caseId, action, remark, version, feedbackTypes);
    }

    @Test
    void reviewLogsReturnedNewestFirstWithOperatorName() {
        long projectId = createProject("O8A");
        long caseId = createCase(projectId, "O8 历史评审用例");
        review(caseId, 1, ReviewAction.NEED_CHANGE, "步骤缺少预期", List.of("step_inaccurate"));
        jdbcTemplate.update("INSERT INTO tp_review_log (case_id, user_id, action, remark) VALUES (?, ?, ?, ?)",
                caseId, reviewerId, "通过", "补充后复审通过");

        List<CaseReviewLogInfo> logs = caseService.listReviewLogs(caseId);

        assertEquals(2, logs.size());
        CaseReviewLogInfo latest = logs.get(0);
        assertEquals("通过", latest.action());
        assertEquals("补充后复审通过", latest.remark());
        assertNotNull(latest.createdAt());
        assertEquals("评审员O8", latest.operatorName());
        CaseReviewLogInfo first = logs.get(1);
        assertEquals("需修改", first.action());
        assertEquals("步骤缺少预期", first.remark());
    }

    @Test
    void emptyLogsWhenNoReview() {
        long projectId = createProject("O8B");
        long caseId = createCase(projectId, "O8 无评审用例");
        List<CaseReviewLogInfo> logs = caseService.listReviewLogs(caseId);
        assertNotNull(logs);
        assertTrue(logs.isEmpty());
    }

    @Test
    void caseNotFoundThrows() {
        ServiceException ex = assertThrows(ServiceException.class, () -> caseService.listReviewLogs(999999999L));
        assertNotNull(ex.getMessage());
        assertFalse(ex.getMessage().isEmpty());
    }
}
