package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.explore.api.dto.ExploreSessionView;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.webelement.api.dto.SiteSurveyDiffView;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whole-site re-survey diff ledger : every run
 * keeps a per-page write tally under the folded page key, and comparing one
 * run against the newest finished earlier run of the same profile yields a
 * review proposal — new pages, vanished pages, per-page candidate deltas.
 * A proposal only lists: it never touches element states on its own (D6:
 * vanished pages do not auto-flip to stale; that stays a human approval).
 * <p>Real dev database, no mocks. Cleanup deletes only rows created here.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class SiteSurveyDiffProposalTest {

    @Autowired
    private SiteSurveyRunLedger runLedger;
    @Autowired
    private SiteSurveyDiffProposal diffProposal;
    @Autowired
    private com.sixbean.testpike.core.webelement.api.controller.SiteSurveyRunInternalController door;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private final List<Long> fixtureProjectIds = new ArrayList<>();
    private final List<Long> fixtureProfileIds = new ArrayList<>();
    private final List<Long> fixtureRunIds = new ArrayList<>();
    private final List<Long> fixtureElementIds = new ArrayList<>();

    @Test
    void okPageFramesTallyWritesUnderTheFoldedPageKey() throws Exception {
        long projectId = createProject();
        long profileId = createProfile(projectId);
        long sessionId = 990_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000);
        long runId = insertRun(profileId, projectId, sessionId, "completed");
        // Two concrete URLs of the same template fold to one page key: the
        // per-page tally must land under the folded key, never under either
        // concrete URL, and must accumulate instead of overwrite.
        runLedger.notePageFrame(session(sessionId),
                pageFrame("https://shop.example/pets/123", "ok"), 3);
        runLedger.notePageFrame(session(sessionId),
                pageFrame("https://shop.example/pets/456", "ok"), 2);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT page_writes FROM tp_site_survey_run WHERE id = ?", runId);
        Map<String, Integer> tallies = readTally(String.valueOf(row.get("page_writes")));
        assertEquals(1, tallies.size(),
                "both concrete URLs must fold into one page key, got " + tallies);
        int total = tallies.values().iterator().next();
        assertEquals(5, total, "writes accumulate across pages of the same key");
        assertTrue(tallies.keySet().iterator().next().contains("/pets/"));
    }

    @Test
    void failedPageFramesNeverEnterThePerPageTally() throws Exception {
        long projectId = createProject();
        long profileId = createProfile(projectId);
        long sessionId = 991_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000);
        long runId = insertRun(profileId, projectId, sessionId, "completed");
        runLedger.notePageFrame(session(sessionId),
                pageFrame("https://shop.example/pets/777", "ok"), 4);
        runLedger.notePageFrame(session(sessionId),
                pageFrame("https://shop.example/report/9", "failed_login"), 0);
        Map<String, Integer> tallies = readTally(jdbcTemplate.queryForObject(
                "SELECT page_writes FROM tp_site_survey_run WHERE id = ?", String.class, runId));
        assertEquals(1, tallies.size(), "login-wall pages stay out of the tally");
    }

    @Test
    void proposalSplitsNewVanishedAndChangedPagesAgainstThePreviousRun() {
        long projectId = createProject();
        long profileId = createProfile(projectId);
        long prevRunId = insertRunWithTally(profileId, projectId, "completed",
                "{\"/orders/{id}\":3,\"/cart\":2}");
        long currRunId = insertRunWithTally(profileId, projectId, "completed",
                "{\"/cart\":5,\"/account\":1}");
        SiteSurveyDiffView view = diffProposal.proposal(profileId, currRunId);
        assertEquals(currRunId, view.getCurrentRunId());
        assertEquals(prevRunId, view.getBaselineRunId(),
                "newest earlier finished run of the same profile is the baseline");
        assertEquals(List.of("/account"), view.getNewPages());
        assertEquals(List.of("/orders/{id}"), view.getVanishedPages());
        assertEquals(1, view.getChangedPages().size());
        SiteSurveyDiffPageDelta cart = view.getChangedPages().get(0);
        assertEquals("/cart", cart.getPageKey());
        assertEquals(2, cart.getBefore());
        assertEquals(5, cart.getAfter());
    }

    @Test
    void firstSurveyProposalTreatsEveryPageAsNew() {
        long projectId = createProject();
        long profileId = createProfile(projectId);
        long runId = insertRunWithTally(profileId, projectId, "completed",
                "{\"/home\":4,\"/about\":1}");
        SiteSurveyDiffView view = diffProposal.proposal(profileId, runId);
        assertTrue(view.isFirstSurvey(), "no earlier finished run means first survey");
        assertEquals(List.of("/about", "/home"), view.getNewPages(), "sorted stable order");
        assertTrue(view.getVanishedPages().isEmpty());
        assertTrue(view.getChangedPages().isEmpty());
    }

    @Test
    void proposalListsVanishedPagesWithoutChangingAnyElementState() {
        long projectId = createProject();
        long profileId = createProfile(projectId);
        insertRunWithTally(profileId, projectId, "completed", "{\"/orders/{id}\":3}");
        long currRunId = insertRunWithTally(profileId, projectId, "completed", "{\"/cart\":1}");
        // A confirmed element living on the vanished page must keep its state:
        // the D6 proposal is one-way — flipping to stale requires human approval.
        Long elementId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_web_element (project_id, name, page_key, state) "
                        + "VALUES (?, ?, '/orders/{id}', 1) RETURNING id",
                Long.class, projectId, "订单按钮" + tail());
        fixtureElementIds.add(elementId);
        SiteSurveyDiffView view = diffProposal.proposal(profileId, currRunId);
        assertEquals(List.of("/orders/{id}"), view.getVanishedPages());
        Integer state = jdbcTemplate.queryForObject(
                "SELECT state FROM tp_web_element WHERE id = ?", Integer.class, elementId);
        assertEquals(1, state, "a proposal never demotes a confirmed element");
    }

    @Test
    void cancelledRunsAreSkippedWhenChoosingTheBaseline() {
        long projectId = createProject();
        long profileId = createProfile(projectId);
        insertRunWithTally(profileId, projectId, "completed", "{\"/a\":1}");
        insertRunWithTally(profileId, projectId, "cancelled", "{\"/b\":9}");
        long currRunId = insertRunWithTally(profileId, projectId, "completed", "{\"/a\":2}");
        SiteSurveyDiffView view = diffProposal.proposal(profileId, currRunId);
        assertEquals(List.of(), view.getNewPages());
        assertEquals(List.of(), view.getVanishedPages(),
                "a cancelled run never becomes the diff baseline");
        assertEquals(1, view.getChangedPages().size());
    }

    @Test
    void internalDoorServesTheProposalWithinItsProject() {
        long projectId = createProject();
        long otherProject = createProject();
        long profileId = createProfile(projectId);
        insertRunWithTally(profileId, projectId, "completed", "{\"/old\":2}");
        long runId = insertRunWithTally(profileId, projectId, "completed", "{\"/new\":1}");
        Response<SiteSurveyDiffView> ok = door.diffProposal(runId, projectId);
        assertNotNull(ok.getData());
        assertEquals(List.of("/new"), ok.getData().getNewPages());
        // In-process door calls surface the rejection as the ServiceException
        // the global handler turns into an error Response over HTTP.
        boolean refused;
        try {
            door.diffProposal(runId, otherProject);
            refused = false;
        } catch (com.sixbean.testpike.common.exception.ServiceException crossProject) {
            refused = true;
        }
        assertTrue(refused, "cross-project reads must be refused");
    }

    // ---------------------------------------------------------------- fixtures

    private long createProject() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        return com.sixbean.testpike.core.support.ProjectFixtureRig.create(
                jdbcTemplate, projectService, 1L, fixtureProjectIds, "差异提案项目" + code, code);
    }

    private long createProfile(long projectId) {
        long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_site_profile (project_id, name, start_url, scope_prefix) "
                        + "VALUES (?, ?, 'https://shop.example/pr/', '/pr/') RETURNING id",
                Long.class, projectId, "档案" + tail());
        fixtureProfileIds.add(id);
        return id;
    }

    private long insertRun(long profileId, long projectId, long sessionId, String status) {
        long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_site_survey_run (profile_id, project_id, explore_session_id,"
                        + " status, finished_at) VALUES (?, ?, ?, ?, now()) RETURNING id",
                Long.class, profileId, projectId, sessionId, status);
        fixtureRunIds.add(id);
        return id;
    }

    private long insertRunWithTally(long profileId, long projectId, String status, String tallyJson) {
        long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_site_survey_run (profile_id, project_id, status,"
                        + " page_writes, finished_at) VALUES (?, ?, ?, CAST(? AS JSONB), now())"
                        + " RETURNING id",
                Long.class, profileId, projectId, status, tallyJson);
        fixtureRunIds.add(id);
        return id;
    }

    /** Runs after each other must pick the newest as baseline: ids grow together. */
    private ExploreSessionView session(long sessionId) {
        ExploreSessionView view = new ExploreSessionView();
        view.setId(sessionId);
        return view;
    }

    private String pageFrame(String url, String status) {
        return "{\"name\":\"site-survey-page\",\"arguments\":{\"url\":\"" + url
                + "\",\"status\":\"" + status + "\",\"counters\":{}}}";
    }

    private Map<String, Integer> readTally(String json) throws Exception {
        return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructMapType(
                        java.util.LinkedHashMap.class, String.class, Integer.class));
    }

    private static String tail() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanupFixtureRows() {
        for (Long elementId : fixtureElementIds) {
            jdbcTemplate.update("DELETE FROM tp_web_element WHERE id = ?", elementId);
        }
        for (Long runId : fixtureRunIds) {
            jdbcTemplate.update("DELETE FROM tp_site_survey_run WHERE id = ?", runId);
        }
        for (Long profileId : fixtureProfileIds) {
            jdbcTemplate.update("DELETE FROM tp_site_profile WHERE id = ?", profileId);
        }
        // Projects created through the service path carry a tp_user_project
        // binding the raw delete cannot see (FK violation seen live 2026-11-23):
        // the tracked-project FK walk in the shared rig is the single source.
        ProjectFixtureRig.sweep(jdbcTemplate, fixtureProjectIds);
    }
}
