package com.sixbean.testpike.core.webelement.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementView;
import com.sixbean.testpike.core.webelement.domain.service.WebElementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exploration-driven staleness detection (WE batch S3, : when an
 * exploration session records a page's interactive candidate pool, every
 * confirmed ledger entry of that page is mechanically compared against the
 * pool. Entries whose judgable anchors all miss are moved confirmed→stale
 * through the single-source doors (revision CAS + audit event); xpath/alt/rel
 * anchors are not decidable from the G4 pool shape and never fabricate a
 * verdict; a truncated pool is absence-without-evidence, so no stale marking
 * happens at all. Pure verdict helpers are asserted directly, the DB round
 * asserts the marking, skipping and idempotent replay posture.
 * Real dev database, no mocks. Cleanup deletes only rows created here.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=false")
class ExploreAnchorReconcileTest {

    @Autowired
    private WebElementService webElementService;
    @Autowired
    private ExploreAnchorReconcile reconcile;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> fixtureProjectIds = new ArrayList<>();

    private long createProject() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        return ProjectFixtureRig.create(jdbcTemplate, projectService, 1L,
                fixtureProjectIds, "察觉账本项目" + code, code);
    }

    @AfterEach
    void cleanUp() {
        for (Long projectId : fixtureProjectIds) {
            jdbcTemplate.update(
                    "DELETE FROM tp_ai_task_event WHERE job_kind = 'element-ledger'"
                            + " AND job_ref IN (SELECT id::text FROM tp_web_element WHERE project_id = ?)",
                    projectId);
            jdbcTemplate.update("DELETE FROM tp_web_element WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        }
        fixtureProjectIds.clear();
    }

    private WebElementView confirmed(long projectId, String name, String pageUrl,
                                     List<WebElementAnchorInput> anchors) {
        WebElementView created = webElementService.create(
                new WebElementInput(projectId, name, pageUrl, anchors));
        return webElementService.changeState(created.id(), 1, created.revision());
    }

    /** Pool carrying one testid button and one placeholder input. */
    private String poolWithLoginButton() {
        return """
                {"candidates":[
                   {"tag":"button","text":"登录","target":{"css":"[data-test=\\"login-btn\\"]"}},
                   {"tag":"input","text":"","target":{"css":"[placeholder=\\"用户名\\"]"}}
                 ],"count":2,"truncated":false}""";
    }

    private String emptyPool() {
        return "{\"candidates\":[],\"count\":0,\"truncated\":false}";
    }

    private String truncatedEmptyPool() {
        return "{\"candidates\":[],\"count\":0,\"truncated\":true}";
    }

    @Test
    void anchorFormsJudgedFromPoolMatchExactFoldedAndTextDialects() {
        ExploreAnchorReconcile.Pool pool = ExploreAnchorReconcile.parsePool(poolWithLoginButton());
        assertEquals(ExploreAnchorReconcile.Hit.HIT,
                ExploreAnchorReconcile.judge(new WebElementAnchorInput("testid", "login-btn", 0.9, null), pool));
        assertEquals(ExploreAnchorReconcile.Hit.HIT,
                ExploreAnchorReconcile.judge(new WebElementAnchorInput("css", "[data-test=\"login-btn\"]", 0.5, null), pool));
        assertEquals(ExploreAnchorReconcile.Hit.HIT,
                ExploreAnchorReconcile.judge(new WebElementAnchorInput("placeholder", "用户名", 0.6, null), pool));
        assertEquals(ExploreAnchorReconcile.Hit.HIT,
                ExploreAnchorReconcile.judge(new WebElementAnchorInput("text", "登录", 0.6, null), pool));
        assertEquals(ExploreAnchorReconcile.Hit.MISS,
                ExploreAnchorReconcile.judge(new WebElementAnchorInput("testid", "gone-btn", 0.9, null), pool));
        assertEquals(ExploreAnchorReconcile.Hit.NOT_JUDGABLE,
                ExploreAnchorReconcile.judge(new WebElementAnchorInput("xpath", "//button", 0.3, null), pool));
        assertEquals(ExploreAnchorReconcile.Hit.NOT_JUDGABLE,
                ExploreAnchorReconcile.judge(new WebElementAnchorInput("rel", "below:搜索框", 0.4, null), pool));
    }

    @Test
    void confirmedEntriesAllMissMovedStaleWhileHitEntryUntouched() {
        long projectId = createProject();
        String page = "http://t654.local/login";
        WebElementView alive = confirmed(projectId, "登录按钮", page,
                List.of(new WebElementAnchorInput("testid", "login-btn", 0.9, "强锚")));
        WebElementView broken = confirmed(projectId, "已改版按钮", page,
                List.of(new WebElementAnchorInput("testid", "old-btn", 0.9, null)));
        int eventsBefore = countEvents(broken.id());

        ExploreAnchorReconcile.Result result =
                reconcile.reconcile(projectId, page, poolWithLoginButton());

        assertEquals(2, result.checked());
        assertEquals(List.of(alive.id()), result.matched());
        assertEquals(List.of(broken.id()), result.mismatched());
        assertTrue(result.staleMarked());
        assertEquals(2, webElementService.findById(broken.id()).state());
        assertEquals(1, webElementService.findById(alive.id()).state());
        assertEquals(eventsBefore + 1, countEvents(broken.id()));
        assertEquals(countEvents(alive.id()), eventsBefore);
    }

    @Test
    void notJudgableOnlyEntriesAndStaleRowsStayOutOfTheMismatchSet() {
        long projectId = createProject();
        String page = "http://t654.local/only";
        WebElementView xpathOnly = confirmed(projectId, "只带xpath", page,
                List.of(new WebElementAnchorInput("xpath", "//button[text()='登录']", 0.3, null)));
        // A stale row is not re-examined (replay posture): only confirmed rows count.
        WebElementView alreadyStale = confirmed(projectId, "已陈旧", page,
                List.of(new WebElementAnchorInput("testid", "gone-btn", 0.9, null)));
        WebElementView staleAgain = webElementService.findById(alreadyStale.id());
        webElementService.changeState(staleAgain.id(), 2, staleAgain.revision());

        ExploreAnchorReconcile.Result result =
                reconcile.reconcile(projectId, page, emptyPool());

        assertEquals(1, result.checked());
        assertEquals(List.of(xpathOnly.id()), result.notJudgable());
        assertTrue(result.mismatched().isEmpty());
        assertFalse(result.staleMarked());
    }

    @Test
    void truncatedPoolReportsCheckedButMarksNothing() {
        long projectId = createProject();
        String page = "http://t654.local/trunc";
        WebElementView broken = confirmed(projectId, "缺席但池裁尾", page,
                List.of(new WebElementAnchorInput("testid", "gone-btn", 0.9, null)));

        ExploreAnchorReconcile.Result result =
                reconcile.reconcile(projectId, page, truncatedEmptyPool());

        assertEquals(1, result.checked());
        assertEquals(List.of(broken.id()), result.mismatched());
        assertFalse(result.staleMarked());
        assertEquals(1, webElementService.findById(broken.id()).state());
        assertEquals(countEvents(broken.id()), 1);
    }

    private int countEvents(long elementId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_task_event WHERE job_kind = 'element-ledger'"
                        + " AND job_ref = ?",
                Integer.class, String.valueOf(elementId));
        return n == null ? 0 : n;
    }
}
