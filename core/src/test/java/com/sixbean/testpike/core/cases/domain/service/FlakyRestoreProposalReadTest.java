package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.FlakyRestoreProposal;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Flaky restore-proposal read channel test .
 * <p>Covers the contract the cases page consumes: a state-1 proposal row surfaces as
 * {caseId, caseNo, caseTitle, greenStreak, greenThreshold, proposedAt}; sessions still
 * probing (state 0) stay absent; results are scoped to the requested project; the
 * threshold rides the live value of the hot key re-read per call (tuning takes effect
 * without touching the ledger); and the read is strictly zero-write - clearing the flaky
 * mark stays a human confirmation through the existing PUT /cases/{id}/flaky door, never
 * a side effect of reading.
 * <p>Real dev database, no mocks (strict testing standard). The proposal fixture reaches
 * state 1 the only honest way: quarantine + terminal green runs past the threshold + a
 * real sweep (the ledger opens its own session rows; manual inserts are banned). Fixtures
 * are self-created and cleaned by id; factory baseline tables are never truncated (R37).
 */
@SpringBootTest(classes = CoreApplication.class)
class FlakyRestoreProposalReadTest {

    private static final String KEY_ENABLED = "ai.flaky.probe.enabled";
    private static final String KEY_INTERVAL = "ai.flaky.probe.interval-minutes";
    private static final String KEY_GREEN = "ai.flaky.probe.green-count";

    @Autowired
    private FlakyProbeService flakyProbeService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long otherProjectId;

    @BeforeEach
    void createProjects() {
        projectId = newProject("FR读通道项目");
        otherProjectId = newProject("FR读通道旁项目");
        setKey(KEY_ENABLED, "true", "bool");
        setKey(KEY_INTERVAL, "30", "int");
        setKey(KEY_GREEN, "2", "int");
    }

    @AfterEach
    void cleanFixtures() {
        for (long project : new long[]{projectId, otherProjectId}) {
            jdbcTemplate.update("DELETE FROM tp_flaky_probe WHERE project_id = ?", project);
            jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id = ?", project);
            jdbcTemplate.update("DELETE FROM tp_notification WHERE project_id = ?", project);
            jdbcTemplate.update("DELETE FROM tp_test_case WHERE project_id = ?", project);
            jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", project);
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", project);
        }
        setKey(KEY_ENABLED, "true", "bool");
        setKey(KEY_GREEN, "5", "int");
        setKey(KEY_INTERVAL, "30", "int");
    }

    // ---- helpers ---------------------------------------------------------------

    private long newProject(String name) {
        return projectService.create(1L, new ProjectCreateInput(name,
                "fr" + UUID.randomUUID().toString().substring(0, 8), "zh-CN", "P1", null));
    }

    /** Quarantined case inside the given project (the ledger opens its own session). */
    private long quarantineCase(long project) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_test_case (case_no, project_id, title, status, flaky,
                                          flaky_reason, flaky_marked_at)
                VALUES (?, ?, '转正读通道夹具用例', 3, 1, '连续结果翻转', now())
                RETURNING id
                """, Long.class,
                "TC-FR-" + UUID.randomUUID().toString().substring(0, 12), project);
    }

    private void addRun(long project, long caseId, int status) {
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, status, started_at, finished_at)
                VALUES (?, ?, ?, now(), now())
                """, project, caseId, status);
    }

    private void setKey(String key, String value, String type) {
        Long id = jdbcTemplate.query(
                "SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, key);
        ConfigInput input = new ConfigInput(key, value, "系统", type, 0,
                "不稳定用例回探参数（夹具写入，测后复位）");
        if (id == null) {
            configService.create(1L, input);
        } else {
            configService.update(1L, id, input);
        }
    }

    /** One sweep after enough green runs, leaving the session as a state-1 proposal. */
    private void reachProposal(long caseId) {
        flakyProbeService.sweep(); // opens the ledger session for the quarantined case
        addRun(projectId, caseId, 3);
        addRun(projectId, caseId, 3);
        flakyProbeService.sweep(); // folds to the threshold and raises the proposal
    }

    private Timestamp probeUpdatedAt(long caseId) {
        return jdbcTemplate.queryForObject("""
                SELECT updated_at FROM tp_flaky_probe
                WHERE case_id = ? AND state = 1
                """, Timestamp.class, caseId);
    }

    // ---- tests -----------------------------------------------------------------

    @Test
    void aPendingProposalSurfacesWithCaseIdentityAndStreak() {
        long caseId = quarantineCase(projectId);
        reachProposal(caseId);
        String caseNo = jdbcTemplate.queryForObject(
                "SELECT case_no FROM tp_test_case WHERE id = ?", String.class, caseId);

        List<FlakyRestoreProposal> proposals = flakyProbeService.pendingProposals(projectId);
        assertEquals(1, proposals.size());
        FlakyRestoreProposal proposal = proposals.get(0);
        assertEquals(caseId, proposal.caseId());
        assertEquals(caseNo, proposal.caseNo());
        assertEquals("转正读通道夹具用例", proposal.caseTitle());
        assertEquals(2, proposal.greenStreak());
        assertEquals(2, proposal.greenThreshold(), "the proposal row reached the configured bar");
        assertNotNull(proposal.proposedAt());
    }

    @Test
    void sessionsStillProbingStayAbsentFromTheProposalRead() {
        long proposed = quarantineCase(projectId);
        reachProposal(proposed);
        long probing = quarantineCase(projectId); // opened at state 0, no green evidence yet
        flakyProbeService.sweep();

        List<FlakyRestoreProposal> proposals = flakyProbeService.pendingProposals(projectId);
        assertEquals(1, proposals.size(), "only state-1 proposals ride the read channel");
        assertEquals(proposed, proposals.get(0).caseId());
    }

    @Test
    void theReadChannelIsScopedToTheRequestedProject() {
        long mine = quarantineCase(projectId);
        reachProposal(mine);
        long theirs = quarantineCase(otherProjectId);
        flakyProbeService.sweep(); // open the other session
        addRun(otherProjectId, theirs, 3);
        addRun(otherProjectId, theirs, 3);
        flakyProbeService.sweep(); // propose there too

        List<FlakyRestoreProposal> mineView = flakyProbeService.pendingProposals(projectId);
        assertEquals(1, mineView.size(), "a sibling project proposal never leaks into this view");
        assertEquals(mine, mineView.get(0).caseId());

        List<FlakyRestoreProposal> theirsView = flakyProbeService.pendingProposals(otherProjectId);
        assertEquals(1, theirsView.size());
        assertEquals(theirs, theirsView.get(0).caseId());
    }

    @Test
    void theThresholdRidesTheHotKeyReReadPerCall() {
        long caseId = quarantineCase(projectId);
        reachProposal(caseId);
        assertEquals(2, flakyProbeService.pendingProposals(projectId).get(0).greenThreshold());

        setKey(KEY_GREEN, "7", "int"); // hot retune, ledger rows untouched
        FlakyRestoreProposal after = flakyProbeService.pendingProposals(projectId).get(0);
        assertEquals(7, after.greenThreshold(),
                "the read reports the live bar, not the bar captured when the proposal rose");
        assertEquals(2, after.greenStreak(), "the recorded streak stays the ledger fact");
    }

    @Test
    void anAcceptedCaseLeavesTheReadBeforeTheSweepClosesItsSession() {
        // One-click accept clears the case flaky mark, while the ledger row only turns
        // state 2 on the next sweep round. Inside that window the read must already be
        // silent, otherwise the cases page keeps offering "accept" for a restored case.
        long accepted = quarantineCase(projectId);
        reachProposal(accepted);
        long pending = quarantineCase(projectId);
        reachProposal(pending);

        jdbcTemplate.update("UPDATE tp_test_case SET flaky = 0 WHERE id = ?", accepted);

        Integer acceptedState = jdbcTemplate.queryForObject(
                "SELECT state FROM tp_flaky_probe WHERE case_id = ?", Integer.class, accepted);
        assertEquals(1, acceptedState, "fixture stays inside the window: ledger not swept closed");

        List<FlakyRestoreProposal> proposals = flakyProbeService.pendingProposals(projectId);
        assertEquals(1, proposals.size(), "a restored case must not ride the proposal read");
        assertEquals(pending, proposals.get(0).caseId());
    }

    @Test
    void readingProposalsWritesNothing() {
        long caseId = quarantineCase(projectId);
        reachProposal(caseId);
        Timestamp updatedBefore = probeUpdatedAt(caseId);

        flakyProbeService.pendingProposals(projectId);
        flakyProbeService.pendingProposals(projectId);

        assertEquals(updatedBefore, probeUpdatedAt(caseId),
                "the read channel is zero-write: the ledger row keeps its updated_at");
        assertEquals(1, flakyProbeService.pendingProposals(projectId).size(),
                "replaying the read never consumes or mutates the proposal");
    }
}
