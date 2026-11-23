package com.sixbean.testpike.core.explore.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.explore.api.dto.ExploreCreateInput;
import com.sixbean.testpike.core.explore.api.dto.ExploreStepInput;
import com.sixbean.testpike.core.explore.domain.service.impl.ExploreServiceImpl;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementView;
import com.sixbean.testpike.core.webelement.domain.component.WebElementLifecycle;
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

/**
 * Exploration write-back wiring (WE batch S3, : when an exploration
 * step records the page's interactive candidate pool (the element-candidates
 * collector detail), the step journal also drives the mechanical anchor
 * reconciliation of that page — confirmed entries whose judgable anchors all
 * miss flip confirmed→stale through ExploreAnchorReconcile, a truncated pool
 * reports without marking, and a step without a pool never touches the
 * ledger. The wiring lives in ExploreServiceImpl.recordStep (the single
 * journaling door), so the AI loop needs no second reconciliation path.
 * Real dev database, no mocks. Cleanup deletes only rows created here.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=false")
class ExploreStepCandidatePoolReconcileTest {

    @Autowired
    private ExploreServiceImpl exploreService;
    @Autowired
    private WebElementService webElementService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> fixtureProjectIds = new ArrayList<>();

    private long createProject() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        return ProjectFixtureRig.create(jdbcTemplate, projectService, 1L,
                fixtureProjectIds, "步进察觉账本项目" + code, code);
    }

    @AfterEach
    void cleanUp() {
        for (Long projectId : fixtureProjectIds) {
            jdbcTemplate.update(
                    "DELETE FROM tp_ai_task_event WHERE job_kind = 'element-ledger'"
                            + " AND job_ref IN (SELECT id::text FROM tp_web_element WHERE project_id = ?)",
                    projectId);
            jdbcTemplate.update("DELETE FROM tp_web_element WHERE project_id = ?", projectId);
            // explore sessions/steps created here hang off the project subtree
            jdbcTemplate.update(
                    "DELETE FROM tp_explore_step WHERE session_id IN"
                            + " (SELECT id FROM tp_explore_session WHERE project_id = ?)",
                    projectId);
            jdbcTemplate.update("DELETE FROM tp_explore_session WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        }
        fixtureProjectIds.clear();
    }

    /** Confirmed ledger entry pinned to a testid the pool below never carries. */
    private WebElementView confirmedGoneButton(long projectId, String pageUrl) {
        WebElementView created = webElementService.create(new WebElementInput(projectId,
                "察觉靶按钮-" + UUID.randomUUID().toString().substring(0, 8), pageUrl,
                List.of(new WebElementAnchorInput("testid", "gone-btn", 1.0, null))));
        return webElementService.changeState(created.id(),
                WebElementLifecycle.CONFIRMED, created.revision());
    }

    private String poolJson(boolean truncated) {
        return "{\"truncated\":" + truncated + ",\"candidates\":["
                + "{\"tag\":\"button\",\"text\":\"登录\",\"target\":{\"css\":\"[data-test=\\\"kept-btn\\\"]\"}}]}";
    }

    private String elementCandidatesAction(String pageUrl) {
        return "{\"name\":\"element-candidates\",\"arguments\":{\"url\":\"" + pageUrl + "\"}}";
    }

    private long session(long projectId, String pageUrl) {
        return exploreService.create(1L, projectId, new ExploreCreateInput("察觉演练", pageUrl));
    }

    @Test
    void missingAnchorFlipsStaleWhenStepRecordsCandidatePool() {
        long projectId = createProject();
        String pageUrl = "http://localhost:5173/rev-" + UUID.randomUUID() + "/login";
        WebElementView entry = confirmedGoneButton(projectId, pageUrl);
        long sessionId = session(projectId, pageUrl);

        exploreService.recordStep(sessionId, new ExploreStepInput(1,
                elementCandidatesAction(pageUrl), "{\"ok\":true}",
                null, null, "候选池回写", poolJson(false)));

        assertEquals(WebElementLifecycle.STALE,
                webElementService.findById(entry.id()).state(),
                "step-recorded pool missing the confirmed anchor must flip it stale");
    }

    @Test
    void stepWithoutPoolLeavesLedgerUntouched() {
        long projectId = createProject();
        String pageUrl = "http://localhost:5173/rev-" + UUID.randomUUID() + "/login";
        WebElementView entry = confirmedGoneButton(projectId, pageUrl);
        long sessionId = session(projectId, pageUrl);

        exploreService.recordStep(sessionId, new ExploreStepInput(1,
                "{\"name\":\"page-snapshot\",\"arguments\":{\"url\":\"" + pageUrl + "\"}}",
                "{\"ok\":true}", null, null, "无候选池", null));

        assertEquals(WebElementLifecycle.CONFIRMED,
                webElementService.findById(entry.id()).state(),
                "no pool on the step means no evidence — the ledger stays as-is");
    }

    @Test
    void truncatedPoolReportsWithoutMarking() {
        long projectId = createProject();
        String pageUrl = "http://localhost:5173/rev-" + UUID.randomUUID() + "/login";
        WebElementView entry = confirmedGoneButton(projectId, pageUrl);
        long sessionId = session(projectId, pageUrl);

        exploreService.recordStep(sessionId, new ExploreStepInput(1,
                elementCandidatesAction(pageUrl), "{\"ok\":true}",
                null, null, "截断候选池", poolJson(true)));

        assertEquals(WebElementLifecycle.CONFIRMED,
                webElementService.findById(entry.id()).state(),
                "a truncated pool is absence-without-evidence — nothing may be marked");
    }
}
