package com.sixbean.testpike.core.webelement.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.explore.api.dto.ExploreCreateInput;
import com.sixbean.testpike.core.explore.api.dto.ExploreStepInput;
import com.sixbean.testpike.core.explore.domain.service.ExploreService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WE-S4 segment P2b wiring proof: the survey dispatch entry creates the explore
 * session through the single ExploreService.create chain with the survey
 * marker goal (declared viewport classes appended from the install key
 * element.viewport-classes), and a step of that session journaling a
 * candidate pool lands ledger candidates through the collector's single-source
 * doors. A non-survey goal must not ingest anything (gate proof, negative
 * direction of the same relay). Real dev database, no mocks.
 */
@SpringBootTest(classes = CoreApplication.class)
class WebElementSurveyWiringTest {

    @Autowired
    private WebElementSurveyDispatch surveyDispatch;

    @Autowired
    private ExploreService exploreService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void cleanAndAnchor() {
        SharedDbFixtureSweep.byProject(jdbcTemplate,
                SharedDbFixtureSweep.projectIdByCode(jdbcTemplate, "WE-S4P2B"));
        projectId = projectService.create(1L,
                new ProjectCreateInput("测绘接线项目", "WE-S4P2B", "zh-CN", "P1", null));
    }

    @AfterEach
    void sweep() {
        SharedDbFixtureSweep.byProject(jdbcTemplate, projectId);
    }

    private static final String START_URL = "http://localhost:5173/survey-demo";

    private static final String POOL_JSON = """
            {"candidates":[
              {"tag":"button","name":"提交订单按钮","target":{"css":"#submit-order","testid":"submit-order"}}
            ]}
            """;

    private static final String POOL_STEP_ACTION = """
            {"action":"element-candidates","arguments":{"url":"http://localhost:5173/survey-demo"}}
            """;

    @Test
    void dispatchCreatesSurveySessionDeclaringViewportClasses() {
        configCacheHolder.put("element.viewport-classes", "mobile,tablet");

        long sessionId = surveyDispatch.dispatch(1L, projectId, START_URL);

        String goal = jdbcTemplate.queryForObject(
                "SELECT goal_text FROM tp_explore_session WHERE id = ?",
                String.class, sessionId);
        String startUrl = jdbcTemplate.queryForObject(
                "SELECT start_url FROM tp_explore_session WHERE id = ?",
                String.class, sessionId);
        assertTrue(goal.startsWith(WebElementSurveyCollector.SURVEY_GOAL_PREFIX),
                "goal must carry the survey marker, actual=" + goal);
        assertTrue(goal.contains(START_URL), "goal must carry the page url");
        assertTrue(goal.contains("mobile,tablet"),
                "goal must declare the configured viewport classes");
        assertEquals(START_URL, startUrl);
    }

    @Test
    void surveyStepPoolIngestsLedgerCandidatesAndReplayStaysIdempotent() {
        long sessionId = surveyDispatch.dispatch(1L, projectId, START_URL);

        exploreService.recordStep(sessionId, new ExploreStepInput(1, POOL_STEP_ACTION,
                "{\"ok\":true}", "sk-1", "dk-1", null, POOL_JSON));
        Long afterFirst = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_web_element WHERE project_id = ?", Long.class, projectId);
        assertEquals(1L, afterFirst, "pool item must land exactly one ledger candidate");

        exploreService.recordStep(sessionId, new ExploreStepInput(2, POOL_STEP_ACTION,
                "{\"ok\":true}", "sk-2", "dk-2", null, POOL_JSON));
        Long afterReplay = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_web_element WHERE project_id = ?", Long.class, projectId);
        assertEquals(1L, afterReplay, "same pool replayed must not duplicate rows");
    }

    @Test
    void plainGoalStepPoolDoesNotIngest() {
        long sessionId = exploreService.create(1L, projectId,
                new ExploreCreateInput("摸清登录流程", "http://localhost:5173"));

        exploreService.recordStep(sessionId, new ExploreStepInput(1, POOL_STEP_ACTION,
                "{\"ok\":true}", "sk-1", "dk-1", null, POOL_JSON));

        Long rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_web_element WHERE project_id = ?", Long.class, projectId);
        assertEquals(0L, rows, "non-survey goal must keep the relay closed");
    }

    @Test
    void surveySubmitActCarriesDeclaredTiersIntoTaskEnvelope() {
        configCacheHolder.put("element.viewport-classes", "mobile,tablet");
        long sessionId = surveyDispatch.dispatch(1L, projectId, START_URL);
        String envelope = "{\"kind\":\"explore-act\",\"action\":\"element-candidates\","
                + "\"url\":\"" + START_URL + "\",\"arguments\":{}}";

        long execTaskId = exploreService.submitAct(sessionId, envelope);

        String stored = jdbcTemplate.queryForObject(
                "SELECT action_json FROM tp_exec_task WHERE id = ?", String.class, execTaskId);
        assertTrue(stored.contains("viewportClasses"),
                "survey candidates envelope must gain the tier field, actual=" + stored);
        assertTrue(stored.contains("mobile") && stored.contains("tablet"),
                "declared tiers must reach the executor-visible envelope");

        long plainSession = exploreService.create(1L, projectId,
                new ExploreCreateInput("摸清登录流程", "http://localhost:5173"));
        long plainTaskId = exploreService.submitAct(plainSession, envelope);
        String plainStored = jdbcTemplate.queryForObject(
                "SELECT action_json FROM tp_exec_task WHERE id = ?", String.class, plainTaskId);
        assertEquals(envelope, plainStored,
                "plain-goal submits keep the legacy envelope byte-identical (zero drift)");
    }
}
