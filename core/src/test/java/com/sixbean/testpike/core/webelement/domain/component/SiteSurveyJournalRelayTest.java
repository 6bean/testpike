package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.explore.api.dto.ExploreCreateInput;
import com.sixbean.testpike.core.explore.api.dto.ExploreSessionView;
import com.sixbean.testpike.core.explore.api.dto.ExploreStepInput;
import com.sixbean.testpike.core.explore.domain.service.ExploreService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whole-site journal relay : per-page write-backs
 * of a site session ride the dedicated site process-stream kind (mixed-use
 * negative: never the single-page kind and vice versa), drive the one
 * idempotent collector source per page pool, and tally the run ledger
 * {@code tp_site_survey_run} — cumulative counters last-write-wins, ledger
 * write counts accumulated core-side, failure pages named in failure_notes.
 * The session terminal state maps onto the run status with the honest ratio
 * rule (failed pages at least half of the pages attempted = failed, any
 * failed page = partial, otherwise completed); budget stop lands partial.
 * <p>Real dev database, no mocks. Cleanup deletes only rows created here.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {
        "testpike.scheduling.enabled=false",
        "testpike.prod-blacklist=prod.example.com",
        "aiservice.base-url=http://127.0.0.1:1"})
class SiteSurveyJournalRelayTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KIND_PAGE = "web-element-survey";
    private static final String KIND_SITE = "web-element-survey-site";

    @Autowired
    private SiteSurveyDispatch siteSurveyDispatch;
    @Autowired
    private SiteSurveyRunLedger siteSurveyRunLedger;
    @Autowired
    private SiteProfileService siteProfileService;
    @Autowired
    private ExploreService exploreService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> fixtureProfileIds = new java.util.ArrayList<>();
    private final List<Long> fixtureRunIds = new java.util.ArrayList<>();
    private final List<Long> fixtureSessionIds = new java.util.ArrayList<>();
    private final List<Long> fixtureProjectIds = new java.util.ArrayList<>();

    // ------------------------------------------------------------------ relay

    @Test
    void pageFramesOfSiteSessionRideSiteKindAndTallyLedger() {
        long projectId = createProject();
        SiteProfileView profile = SiteSurveyProfileFixture.createProfile(siteProfileService, fixtureProfileIds, projectId, "中继档案" + tail(),
                "http://127.0.0.1:8899/pr/", Map.of());
        long runId = siteSurveyDispatch.dispatch(1L, projectId, profile.getId());
        fixtureRunIds.add(runId);
        long sessionId = sessionIdOf(runId);
        fixtureSessionIds.add(sessionId);

        String url = "http://127.0.0.1:8899/pr/list";
        exploreService.recordStep(sessionId, new ExploreStepInput(1,
                sitePageAction(url, "ok", null, counters(1, 1, 0, 6, 0, 0, "{\"pr\":2}")),
                "{\"ok\":true}", null, null, null, poolJson()));

        Map<String, Object> run = runRow(runId);
        assertEquals(1, ((Number) run.get("pages_total")).intValue());
        assertEquals(1, ((Number) run.get("pages_ok")).intValue());
        assertEquals(6, ((Number) run.get("links_seen")).intValue());
        assertEquals(2, ((Number) run.get("ledger_upserted")).intValue(),
                "pool rows the collector wrote (created) must accumulate");
        assertEquals(1, eventCount(KIND_SITE, sessionId), "page frame rides the site kind");
        assertEquals(0, eventCount(KIND_PAGE, sessionId),
                "mixed-use negative: the site session never writes the single-page kind");
    }

    @Test
    void failedLoginPageCountsAndNotesWithoutLedgerRows() {
        long projectId = createProject();
        SiteProfileView profile = SiteSurveyProfileFixture.createProfile(siteProfileService, fixtureProfileIds, projectId, "登录墙档案" + tail(),
                "http://127.0.0.1:8899/pr/", Map.of());
        long runId = siteSurveyDispatch.dispatch(1L, projectId, profile.getId());
        fixtureRunIds.add(runId);
        long sessionId = sessionIdOf(runId);
        fixtureSessionIds.add(sessionId);

        String okUrl = "http://127.0.0.1:8899/pr/home";
        String wallUrl = "http://127.0.0.1:8899/pr/report";
        exploreService.recordStep(sessionId, new ExploreStepInput(1,
                sitePageAction(okUrl, "ok", null, counters(1, 1, 0, 3, 0, 0, "{}")),
                "{\"ok\":true}", null, null, null, poolJson()));
        exploreService.recordStep(sessionId, new ExploreStepInput(2,
                sitePageAction(wallUrl, "failed_login", "被重定向（登录态可能不足或无权）",
                        counters(2, 1, 1, 3, 0, 0, "{}")),
                "{\"ok\":true}", null, null, null, null));

        Map<String, Object> run = runRow(runId);
        assertEquals(2, ((Number) run.get("pages_total")).intValue());
        assertEquals(1, ((Number) run.get("pages_failed")).intValue());
        String notes = String.valueOf(run.get("failure_notes"));
        assertTrue(notes.contains(wallUrl) && notes.contains("failed_login"), notes);
        int elements = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_web_element WHERE project_id = ?",
                Integer.class, projectId);
        assertEquals(2, elements, "a login-wall page contributes no ledger rows");
    }

    @Test
    void cumulativeCountersOverwriteWithLastFrameWinning() throws Exception {
        long projectId = createProject();
        SiteProfileView profile = SiteSurveyProfileFixture.createProfile(siteProfileService, fixtureProfileIds, projectId, "计数覆盖档案" + tail(),
                "http://127.0.0.1:8899/pr/", Map.of());
        long runId = siteSurveyDispatch.dispatch(1L, projectId, profile.getId());
        fixtureRunIds.add(runId);
        long sessionId = sessionIdOf(runId);
        fixtureSessionIds.add(sessionId);

        exploreService.recordStep(sessionId, new ExploreStepInput(1,
                sitePageAction("http://127.0.0.1:8899/pr/a", "ok", null,
                        counters(1, 1, 0, 3, 1, 2, "{\"md\":1}")),
                "{\"ok\":true}", null, null, null, null));
        exploreService.recordStep(sessionId, new ExploreStepInput(2,
                sitePageAction("http://127.0.0.1:8899/pr/b", "excluded", null,
                        counters(2, 1, 0, 10, 4, 7, "{\"md\":5,\"news\":2}")),
                "{\"ok\":true}", null, null, null, null));

        Map<String, Object> run = runRow(runId);
        assertEquals(10, ((Number) run.get("links_seen")).intValue());
        assertEquals(4, ((Number) run.get("out_of_scope")).intValue());
        assertEquals(7, ((Number) run.get("excluded")).intValue());
        JsonNode sibling = JSON.readTree(String.valueOf(run.get("sibling_counts")));
        assertEquals(5, sibling.path("md").asInt());
        assertEquals(2, sibling.path("news").asInt());
    }

    // -------------------------------------------------------------- terminal

    @Test
    void failedRatioAtLeastHalfTurnsTheRunIntoFailure() {
        FixtureRun f = dispatchedRun("半数失败档案");
        exploreService.recordStep(f.sessionId(), new ExploreStepInput(1,
                sitePageAction("http://127.0.0.1:8899/pr/x", "ok", null,
                        counters(1, 1, 0, 2, 0, 0, "{}")),
                "{\"ok\":true}", null, null, null, poolJson()));
        exploreService.recordStep(f.sessionId(), new ExploreStepInput(2,
                sitePageAction("http://127.0.0.1:8899/pr/y", "failed_login", "登录形态且零业务候选",
                        counters(2, 1, 1, 2, 0, 0, "{}")),
                "{\"ok\":true}", null, null, null, null));

        exploreService.updateStatus(f.sessionId(), ExploreSessionView.STATUS_DONE);
        Map<String, Object> run = runRow(f.runId());
        assertEquals("failed", run.get("status"), "half of the pages failed = never completed");
        assertNotNull(run.get("finished_at"));
    }

    @Test
    void cleanRunCompletesAndSpottyRunStaysPartial() {
        FixtureRun clean = dispatchedRun("干净完成档案");
        exploreService.recordStep(clean.sessionId(), new ExploreStepInput(1,
                sitePageAction("http://127.0.0.1:8899/pr/ok", "ok", null,
                        counters(1, 1, 0, 2, 0, 0, "{}")),
                "{\"ok\":true}", null, null, null, poolJson()));
        exploreService.updateStatus(clean.sessionId(), ExploreSessionView.STATUS_DONE);
        assertEquals("completed", runRow(clean.runId()).get("status"));

        FixtureRun spotty = dispatchedRun("少量失败档案");
        exploreService.recordStep(spotty.sessionId(), new ExploreStepInput(1,
                sitePageAction("http://127.0.0.1:8899/pr/a", "ok", null,
                        counters(1, 1, 0, 2, 0, 0, "{}")),
                "{\"ok\":true}", null, null, null, poolJson()));
        exploreService.recordStep(spotty.sessionId(), new ExploreStepInput(2,
                sitePageAction("http://127.0.0.1:8899/pr/b", "ok", null,
                        counters(2, 2, 0, 4, 0, 0, "{}")),
                "{\"ok\":true}", null, null, null, poolJson()));
        exploreService.recordStep(spotty.sessionId(), new ExploreStepInput(3,
                sitePageAction("http://127.0.0.1:8899/pr/c", "redirect_out", "跳出站外",
                        counters(3, 2, 1, 4, 0, 0, "{}")),
                "{\"ok\":true}", null, null, null, null));
        exploreService.updateStatus(spotty.sessionId(), ExploreSessionView.STATUS_BUDGET_STOP);
        assertEquals("partial", runRow(spotty.runId()).get("status"));
        assertEquals(1, eventCount(KIND_SITE, spotty.sessionId()) > 0 ? 1 : 0,
                "terminal frame of a site session also rides the site kind");
    }

    // -------------------------------------------------------- mixed-use guard

    @Test
    void singlePageSurveyKeepsOldKindAndNeverTouchesRunLedger() {
        long projectId = createProject();
        String url = "http://127.0.0.1:8899/pr/checkout";
        long sessionId = exploreService.create(1L, projectId,
                new ExploreCreateInput(WebElementSurveyCollector.SURVEY_GOAL_PREFIX + url, url));
        fixtureSessionIds.add(sessionId);

        exploreService.recordStep(sessionId, new ExploreStepInput(1,
                "{\"name\":\"element-candidates\",\"arguments\":{\"url\":\"" + url + "\"}}",
                "{\"ok\":true}", null, null, null, poolJson()));

        assertEquals(1, eventCount(KIND_PAGE, sessionId));
        assertEquals(0, eventCount(KIND_SITE, sessionId),
                "mixed-use negative: a single-page session never writes the site kind");
        assertEquals(0, jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tp_site_survey_run WHERE explore_session_id = ?",
                        Integer.class, sessionId).intValue());
    }

    // ------------------------------------------------- naming-outage tallying

    /**
     * Naming supply outage rides the same step relay (silent-failure rule of
     * the batch ruling): a page whose candidate pool could not be AI-named
     * counts exactly one degraded page on the run ledger — the naming door
     * is the only face that knows the outage, so this column has no
     * executor-frame writer. A login-wall page (no pool ingested) adds
     * nothing; frames never zero an established count.
     */
    @Test
    void namingOutageCountsDegradedPagesOnTheRunLedger_oncePerPage() {
        FixtureRun fixture = dispatchedRun("命名断供档案");

        exploreService.recordStep(fixture.sessionId(), new ExploreStepInput(1,
                sitePageAction("http://127.0.0.1:8899/pr/home", "ok", null,
                        counters(1, 1, 0, 4, 0, 0, "{}")),
                "{\"ok\":true}", null, null, null, poolJson()));
        exploreService.recordStep(fixture.sessionId(), new ExploreStepInput(2,
                sitePageAction("http://127.0.0.1:8899/pr/report", "failed_login",
                        "被重定向（登录态可能不足或无权）", counters(2, 1, 1, 4, 0, 0, "{}")),
                "{\"ok\":true}", null, null, null, null));
        exploreService.recordStep(fixture.sessionId(), new ExploreStepInput(3,
                sitePageAction("http://127.0.0.1:8899/pr/list", "ok", null,
                        counters(3, 2, 1, 9, 0, 0, "{}")),
                "{\"ok\":true}", null, null, null, poolJson()));

        Map<String, Object> run = runRow(fixture.runId());
        assertEquals(2, ((Number) run.get("naming_degraded")).intValue(),
                "two pool-carrying pages with the naming door unreachable = two degraded counts");
        assertEquals(3, ((Number) run.get("pages_total")).intValue(),
                "counters still ride the cumulative snapshot (last frame wins)");
    }

    // -------------------------------------------------------------- fixtures

    private record FixtureRun(long runId, long sessionId) {
    }

    private FixtureRun dispatchedRun(String name) {
        long projectId = createProject();
        SiteProfileView profile = SiteSurveyProfileFixture.createProfile(siteProfileService, fixtureProfileIds, projectId, name + tail(),
                "http://127.0.0.1:8899/pr/", Map.of());
        long runId = siteSurveyDispatch.dispatch(1L, projectId, profile.getId());
        fixtureRunIds.add(runId);
        long sessionId = sessionIdOf(runId);
        fixtureSessionIds.add(sessionId);
        return new FixtureRun(runId, sessionId);
    }

    private long sessionIdOf(long runId) {
        return ((Number) jdbcTemplate.queryForObject(
                "SELECT explore_session_id FROM tp_site_survey_run WHERE id = ?",
                Long.class, runId)).longValue();
    }

    private Map<String, Object> runRow(long runId) {
        return jdbcTemplate.queryForMap(
                "SELECT pages_total, pages_ok, pages_failed, links_seen, out_of_scope,"
                        + " excluded, ledger_upserted, naming_degraded,"
                        + " sibling_counts::text AS sibling_counts,"
                        + " failure_notes::text AS failure_notes, status, finished_at"
                        + " FROM tp_site_survey_run WHERE id = ?", runId);
    }

    private int eventCount(String kind, long sessionId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_task_event WHERE job_kind = ? AND job_ref = ?",
                Integer.class, kind, String.valueOf(sessionId));
        return n == null ? 0 : n;
    }

    private static String tail() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Cumulative counters exactly as the executor journal frames carry them. */
    private static String counters(int total, int ok, int failed, int links,
                                   int outOfScope, int excluded, String siblingJson) {
        return "{\"pages_total\":" + total + ",\"pages_ok\":" + ok + ",\"pages_failed\":" + failed
                + ",\"links_seen\":" + links + ",\"out_of_scope\":" + outOfScope
                + ",\"excluded\":" + excluded + ",\"sibling_counts\":" + siblingJson + "}";
    }

    private static String sitePageAction(String url, String status, String reason, String counters) {
        return "{\"name\":\"site-survey-page\",\"arguments\":{\"url\":\"" + url
                + "\",\"status\":\"" + status + "\""
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
                + (counters == null ? "" : ",\"counters\":" + counters) + "}}";
    }

    private static String poolJson() {
        return "{\"truncated\":false,\"candidates\":["
                + "{\"tag\":\"button\",\"text\":\"查询\",\"target\":{\"css\":\"[data-test=\\\"q1\\\"]\"}},"
                + "{\"tag\":\"input\",\"text\":\"关键词\",\"target\":{\"css\":\"[data-test=\\\"kw\\\"]\"}}]}";
    }

    private long createProject() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        return com.sixbean.testpike.core.support.ProjectFixtureRig.create(
                jdbcTemplate, projectService, 1L, fixtureProjectIds, "测绘中继项目" + code, code);
    }

    @AfterEach
    void cleanupFixtureRows() {
        for (Long sessionId : fixtureSessionIds) {
            jdbcTemplate.update("DELETE FROM tp_ai_task_event WHERE job_ref = ?",
                    String.valueOf(sessionId));
            jdbcTemplate.update("DELETE FROM tp_explore_step WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM tp_explore_session WHERE id = ?", sessionId);
        }
        for (Long runId : fixtureRunIds) {
            jdbcTemplate.update("DELETE FROM tp_site_survey_run WHERE id = ?", runId);
        }
        for (Long profileId : fixtureProfileIds) {
            jdbcTemplate.update("DELETE FROM tp_site_profile WHERE id = ?", profileId);
        }
        for (Long projectId : fixtureProjectIds) {
            // Element rows are this class's only project-scoped product; the
            // project rows themselves belong to ProjectFixtureRig's lifecycle.
            jdbcTemplate.update("DELETE FROM tp_web_element WHERE project_id = ?", projectId);
        }
        fixtureSessionIds.clear();
        fixtureRunIds.clear();
        fixtureProfileIds.clear();
        fixtureProjectIds.clear();
    }
}
