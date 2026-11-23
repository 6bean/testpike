package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.webelement.api.dto.SiteProfileView;
import com.sixbean.testpike.core.webelement.domain.service.SiteProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whole-site survey dispatch door : the goal
 * prefix is the routing marker shared with the single-page collector gate, so
 * per-page candidate pools keep flowing through the ONE idempotent upsert
 * source. Dispatch creates exactly one explore session (no second INSERT
 * path) plus one running ledger row whose rules_snapshot freezes the profile
 * rules merged with the install keys at dispatch time — a later profile edit
 * must never rewrite a run in flight. Production hosts stay refused while the
 * allow switch is off, and the refusal names the matched host, the settings
 * page and the config key (R63/R32 guidance posture).
 * <p>Real dev database, no mocks. Cleanup deletes only rows created here.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {
        "testpike.scheduling.enabled=false",
        "testpike.prod-blacklist=prod.example.com"})
class SiteSurveyDispatchTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private SiteSurveyDispatch siteSurveyDispatch;
    @Autowired
    private SiteProfileService siteProfileService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.List<Long> fixtureProfileIds = new java.util.ArrayList<>();
    private final java.util.List<Long> fixtureRunIds = new java.util.ArrayList<>();
    private final java.util.List<Long> fixtureSessionIds = new java.util.ArrayList<>();
    private final java.util.List<Long> fixtureProjectIds = new java.util.ArrayList<>();

    // ---------------------------------------------------------------- routing

    @Test
    void wholeSiteGoalRoutesThroughTheSharedSurveyGate() {
        assertTrue(WebElementSurveyCollector.isSiteSurveyGoal("整站测绘：42 https://xx.com/pr/"));
        assertFalse(WebElementSurveyCollector.isSiteSurveyGoal("整页测绘：https://xx.com/pr/"));
        assertFalse(WebElementSurveyCollector.isSiteSurveyGoal("自由探索登录流程"));
        assertFalse(WebElementSurveyCollector.isSiteSurveyGoal(null));
        // The ingest gate accepts BOTH survey dialects so the relay keeps one
        // collector source for per-page pools (no second INSERT path).
        assertTrue(WebElementSurveyCollector.isSurveyGoal("整页测绘：https://xx.com/pr/"));
        assertTrue(WebElementSurveyCollector.isSurveyGoal("整站测绘：42 https://xx.com/pr/"));
        assertFalse(WebElementSurveyCollector.isSurveyGoal("自由探索登录流程"));
    }

    @Test
    void wholeSiteStreamOwnsItsNewProcessFrameKind() {
        assertEquals("web-element-survey-site",
                com.sixbean.testpike.core.events.domain.component
                        .AiTaskEventTrimSweeper.KIND_WEB_ELEMENT_SURVEY_SITE);
    }

    // ---------------------------------------------------------------- dispatch

    @Test
    void dispatchCreatesOneSessionAndOneRunningRowWithFrozenRules() throws Exception {
        long projectId = createProject();
        SiteProfileView profile = SiteSurveyProfileFixture.createProfile(siteProfileService, fixtureProfileIds, projectId, "商城前台" + tail(),
                "http://127.0.0.1:8899/pr/", Map.of());

        long runId = siteSurveyDispatch.dispatch(1L, projectId, profile.getId());
        fixtureRunIds.add(runId);

        Map<String, Object> run = jdbcTemplate.queryForMap(
                "SELECT profile_id, project_id, explore_session_id, status, rules_snapshot::text AS snap"
                        + " FROM tp_site_survey_run WHERE id = ?", runId);
        assertEquals(profile.getId(), ((Number) run.get("profile_id")).longValue());
        assertEquals(projectId, ((Number) run.get("project_id")).longValue());
        assertEquals("running", run.get("status"));
        long sessionId = ((Number) run.get("explore_session_id")).longValue();
        fixtureSessionIds.add(sessionId);
        assertTrue(sessionId > 0, "dispatch must ride the shared explore create chain");

        String goal = jdbcTemplate.queryForObject(
                "SELECT goal_text FROM tp_explore_session WHERE id = ?", String.class, sessionId);
        assertNotNull(goal);
        assertTrue(goal.startsWith(WebElementSurveyCollector.SITE_SURVEY_GOAL_PREFIX), goal);
        assertTrue(goal.contains(String.valueOf(profile.getId())), goal);

        JsonNode snap = JSON.readTree((String) run.get("snap"));
        assertEquals("http://127.0.0.1:8899/pr/", snap.path("startUrl").asText());
        assertTrue(snap.has("excludeGlobs") && snap.has("ignoreParams")
                && snap.has("guardVerbs") && snap.has("verbsGuardEnabled"), snap.toString());
        assertTrue(snap.has("maxPages") && snap.has("maxDepth") && snap.has("intervalMs")
                && snap.has("deadlineSeconds") && snap.has("maxLinksPerPage")
                && snap.has("perTemplateCap"), snap.toString());
        assertFalse(snap.path("allowProd").asBoolean(true), "factory default refuses prod");
        assertTrue(snap.toString().contains("prod.example.com"), "deny list rides the snapshot");
    }

    @Test
    void profileBudgetOverridesWinInsideTheFrozenSnapshot() throws Exception {
        long projectId = createProject();
        SiteProfileView profile = SiteSurveyProfileFixture.createProfile(siteProfileService, fixtureProfileIds, projectId, "内容站" + tail(),
                "http://127.0.0.1:8899/md/", Map.of("maxPages", 5, "perTemplateCap", 3));

        long runId = siteSurveyDispatch.dispatch(1L, projectId, profile.getId());
        fixtureRunIds.add(runId);

        String snapJson = jdbcTemplate.queryForObject(
                "SELECT rules_snapshot::text FROM tp_site_survey_run WHERE id = ?",
                String.class, runId);
        JsonNode snap = JSON.readTree(snapJson);
        assertEquals(5, snap.path("maxPages").asInt());
        assertEquals(3, snap.path("perTemplateCap").asInt());
        // untouched budget keys keep the install defaults (maxDepth 6, interval 800)
        assertEquals(6, snap.path("maxDepth").asInt());
        assertEquals(800, snap.path("intervalMs").asInt());
    }

    @Test
    void productionHostDispatchIsRefusedAndNamesTheWayToProceed() {
        long projectId = createProject();
        SiteProfileView profile = SiteSurveyProfileFixture.createProfile(siteProfileService, fixtureProfileIds, projectId, "生产演练" + tail(),
                "http://prod.example.com/pr/", Map.of());

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> siteSurveyDispatch.dispatch(1L, projectId, profile.getId()));
        String msg = refusal.getMessage();
        assertTrue(msg.contains("prod.example.com"), "must name the matched host: " + msg);
        assertTrue(msg.contains("element.survey-site.allow-prod"),
                "must name the config key literal: " + msg);
        assertTrue(msg.contains("系统设置"), "must name the settings path: " + msg);
    }

    // ---------------------------------------------------------------- fixtures

    private static String tail() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private long createProject() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        return com.sixbean.testpike.core.support.ProjectFixtureRig.create(
                jdbcTemplate, projectService, 1L, fixtureProjectIds, "整站测绘项目" + code, code);
    }

    @AfterEach
    void cleanupFixtureRows() {
        for (Long runId : fixtureRunIds) {
            jdbcTemplate.update("DELETE FROM tp_site_survey_run WHERE id = ?", runId);
        }
        for (Long sessionId : fixtureSessionIds) {
            jdbcTemplate.update("DELETE FROM tp_explore_step WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM tp_explore_session WHERE id = ?", sessionId);
        }
        for (Long profileId : fixtureProfileIds) {
            jdbcTemplate.update("DELETE FROM tp_site_profile WHERE id = ?", profileId);
        }
        fixtureRunIds.clear();
        fixtureSessionIds.clear();
        fixtureProfileIds.clear();
    }
}
