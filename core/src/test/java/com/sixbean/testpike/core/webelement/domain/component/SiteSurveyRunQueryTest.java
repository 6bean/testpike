package com.sixbean.testpike.core.webelement.domain.component;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.webelement.api.dto.SiteProfileView;
import com.sixbean.testpike.common.webelement.SiteSurveyRunView;
import com.sixbean.testpike.core.webelement.domain.service.SiteProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whole-site survey run ledger reads : the drawer
 * face needs one bounded project-scoped list of runs (profile name joined
 * server-side, honest counters, newest first) plus a dispatch door that starts
 * a crawl from a saved profile through the same shared component the tests for
 * pinned — no second INSERT path and no client-claimed project.
 * <p>Real dev database, no mocks. Cleanup deletes only rows created here.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {
        "testpike.scheduling.enabled=false",
        "testpike.prod-blacklist=prod.example.com"})
class SiteSurveyRunQueryTest {

    @Autowired
    private SiteSurveyRunLedger runLedger;
    @Autowired
    private SiteSurveyDispatch siteSurveyDispatch;
    @Autowired
    private SiteProfileService siteProfileService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private com.sixbean.testpike.core.webelement.api.controller.SiteSurveyRunInternalController door;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> fixtureRunIds = new ArrayList<>();
    private final List<Long> fixtureSessionIds = new ArrayList<>();
    private final List<Long> fixtureProfileIds = new ArrayList<>();
    private final List<Long> fixtureProjectIds = new ArrayList<>();

    @Test
    void listRecentIsProjectScopedNewestFirstWithJoinedProfileName() {
        long projectId = createProject();
        SiteProfileView first = SiteSurveyProfileFixture.createProfile(siteProfileService, fixtureProfileIds, projectId, "商城前台甲" + tail(),
                "http://127.0.0.1:8899/pr/", Map.of());
        SiteProfileView second = SiteSurveyProfileFixture.createProfile(siteProfileService, fixtureProfileIds, projectId, "商城前台乙" + tail(),
                "http://127.0.0.1:8899/md/", Map.of());
        long runA = siteSurveyDispatch.dispatch(1L, projectId, first.getId());
        fixtureRunIds.add(runA);
        fixtureSessionIds.add(sessionOf(runA));
        long runB = siteSurveyDispatch.dispatch(1L, projectId, second.getId());
        fixtureRunIds.add(runB);
        fixtureSessionIds.add(sessionOf(runB));

        // A run of another project must never leak into this segment.
        long otherProject = createProject();
        SiteProfileView foreign = SiteSurveyProfileFixture.createProfile(siteProfileService, fixtureProfileIds, otherProject, "他项目档案" + tail(),
                "http://127.0.0.1:8899/other/", Map.of());
        long runForeign = siteSurveyDispatch.dispatch(1L, otherProject, foreign.getId());
        fixtureRunIds.add(runForeign);
        fixtureSessionIds.add(sessionOf(runForeign));

        List<SiteSurveyRunView> rows = runLedger.listRecent(projectId, 50);
        List<Long> runIds = rows.stream().map(SiteSurveyRunView::runId).toList();
        assertTrue(runIds.contains(runA) && runIds.contains(runB), "own runs listed: " + runIds);
        assertFalse(runIds.contains(runForeign), "foreign project run leaked: " + runIds);

        SiteSurveyRunView rowB = rows.stream().filter(r -> r.runId() == runB).findFirst().orElseThrow();
        assertEquals(second.name(), rowB.profileName(), "profile name joined server-side");
        assertEquals("http://127.0.0.1:8899/md/", rowB.startUrl());
        assertEquals("running", rowB.status());
        assertEquals(0, rowB.pagesTotal());
        assertNotNull(rowB.startedAt());
        assertNotNull(rowB.exploreSessionId());

        // Newest first within the segment, and the cap is honoured.
        assertEquals(runB, rows.get(0).runId(), "newest dispatch comes first");
        assertEquals(1, runLedger.listRecent(projectId, 1).size(), "limit caps the page");
    }

    @Test
    void internalDoorsServeTheAdminBridge() {
        long projectId = createProject();
        SiteProfileView profile = SiteSurveyProfileFixture.createProfile(siteProfileService, fixtureProfileIds, projectId, "门面对话档案" + tail(),
                "http://127.0.0.1:8899/shop/", Map.of());

        Response<Long> dispatched = door.dispatchFromProfile(projectId, profile.getId(), 1L);
        long runId = dispatched.getData();
        assertTrue(runId > 0, "dispatch door returns the new run id");
        fixtureRunIds.add(runId);
        fixtureSessionIds.add(sessionOf(runId));

        Response<List<SiteSurveyRunView>> listed = door.listRecent(projectId, 50);
        List<Long> runIds = listed.getData().stream().map(SiteSurveyRunView::runId).toList();
        assertTrue(runIds.contains(runId), "list door sees the dispatched run");

        // The kickoff frame rides the site kind tail keyed by session id so
        // the replay drawer reads it through the one existing cursor door.
        long sessionId = sessionOf(runId);
        Integer frames = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_task_event WHERE job_kind = ? AND job_ref = ?",
                Integer.class, "web-element-survey-site", String.valueOf(sessionId));
        assertNotNull(frames);
        assertTrue(frames >= 1, "dispatch door opens the tail with a kickoff frame");
    }

    private long sessionOf(long runId) {
        return jdbcTemplate.queryForObject(
                "SELECT explore_session_id FROM tp_site_survey_run WHERE id = ?", Long.class, runId);
    }

    private long createProject() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        return com.sixbean.testpike.core.support.ProjectFixtureRig.create(
                jdbcTemplate, projectService, 1L, fixtureProjectIds, "测绘账本项目" + code, code);
    }

    private static String tail() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    @AfterEach
    void cleanupFixtureRows() {
        for (Long runId : fixtureRunIds) {
            jdbcTemplate.update("DELETE FROM tp_site_survey_run WHERE id = ?", runId);
        }
        for (Long sessionId : fixtureSessionIds) {
            jdbcTemplate.update("DELETE FROM tp_ai_task_event WHERE job_kind = ? AND job_ref = ?",
                    "web-element-survey-site", String.valueOf(sessionId));
            jdbcTemplate.update("DELETE FROM tp_explore_step WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM tp_explore_session WHERE id = ?", sessionId);
        }
        for (Long profileId : fixtureProfileIds) {
            jdbcTemplate.update("DELETE FROM tp_site_profile WHERE id = ?", profileId);
        }
        com.sixbean.testpike.core.support.ProjectFixtureRig.sweep(jdbcTemplate, fixtureProjectIds);
        fixtureRunIds.clear();
        fixtureSessionIds.clear();
        fixtureProfileIds.clear();
        fixtureProjectIds.clear();
    }
}
