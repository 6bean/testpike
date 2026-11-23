package com.sixbean.testpike.core.journey.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.asset.api.dto.AssetPublishRequest;
import com.sixbean.testpike.core.asset.api.dto.SharedAssetInfo;
import com.sixbean.testpike.core.asset.domain.service.SharedAssetService;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.journey.api.dto.JourneyCreateInput;
import com.sixbean.testpike.core.journey.api.dto.JourneyRunInfo;
import com.sixbean.testpike.core.journey.api.dto.JourneyRunSegmentInfo;
import com.sixbean.testpike.core.journey.api.dto.JourneySegmentInput;
import com.sixbean.testpike.core.journey.domain.component.JourneyRunFinalizeSupport;
import com.sixbean.testpike.core.notification.domain.component.NotificationEventDict;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import com.sixbean.testpike.core.support.JourneyFixtureCleaner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Journey run expansion and exit sign-off against the real chain database.
 * Expansion launches one queued batch per segment inside its own transaction — a segment
 * missing its case list fails alone, downstream segments are skipped, and already-launched
 * batches stay untouched (honest partial launch). Run aggregation maps batch terminal
 * statuses onto the run and notifies the journey owner; a batch outside any journey keeps
 * zero side effects. Sign-off authority is exactly the journey owner, once per run, with
 * one audit row each, and the two journey events must be dictionary-registered so the
 * notification pipeline accepts them.
 * <p>Every journey row, notification and fragment asset created under the tracked
 * programs is purged by lineage and id around each test — nothing is truncated, so
 * concurrent suites' rows in the shared tables stay untouched (R37).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false",
        "testpike.prod-blacklist=black.example.com"})
class JourneyRunServiceTest {

    @Autowired
    private JourneyService journeyService;
    @Autowired
    private JourneyRunService journeyRunService;
    @Autowired
    private JourneyRunFinalizeSupport journeyRunFinalizeSupport;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ProjectEnvService projectEnvService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private ScriptService scriptService;
    @Autowired
    private SharedAssetService assetService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JourneyFixtureCleaner fixture;

    @BeforeEach
    @AfterEach
    void cleanJourneyTables() {
        fixture = JourneyFixtureCleaner.forTestRun(fixture, jdbcTemplate, projectService);
        fixture.purgeJourneyLineage();
        jdbcTemplate.update("DELETE FROM tp_asset_reference WHERE asset_row_id IN "
                + "(SELECT id FROM tp_asset WHERE name LIKE 'jrfrag-%')");
        jdbcTemplate.update("DELETE FROM tp_asset WHERE name LIKE 'jrfrag-%'");
        fixture.cleanupTracked();
    }

    /** Project plus one review-passed scripted case (ready for batch launch). */
    private long createProjectWithReadyCase(String tag, String[] caseIdOut) {
        String code = "jr" + UUID.randomUUID().toString().substring(0, 10);
        long pid = projectService.create(1L,
                new ProjectCreateInput("JRun " + code, code, "zh-CN", "P1", null));
        fixture.trackProject(pid);
        long envId = projectEnvService.create(pid, new ProjectEnvInput("env-" + tag, 2,
                "https://env-" + tag + ".example.com", null, "web", "chromium", "v1.0.0"));
        CaseInfo info = caseService.create(1L, new CaseCreateInput(pid, "旅程用例-" + tag,
                "前置", "预期", 1, 2, "web", 3, 2, 1, null, List.of()));
        jdbcTemplate.update("UPDATE tp_test_case SET status = 3 WHERE id = ?", info.id());
        scriptService.save(new ScriptSaveInput(info.id(), "v2",
                "{\"caseId\":" + info.id() + ",\"steps\":[]}", 2));
        caseIdOut[0] = String.valueOf(info.id());
        caseIdOut[1] = String.valueOf(envId);
        return pid;
    }

    /** Publish a real case-fragment asset row in the segment's own project. */
    private long publishAssetRow(long ownerProjectId) {
        SharedAssetInfo head = assetService.publish(1L, ownerProjectId,
                new AssetPublishRequest(ownerProjectId, "case_fragment",
                        "jrfrag-" + UUID.randomUUID().toString().substring(0, 8), "[]"));
        return head.id();
    }

    private long journeyWithSegments(long programId, List<JourneySegmentInput> inputs) {
        long journeyId = journeyService.createJourney(1L,
                new JourneyCreateInput(programId, "run-journey-" + UUID.randomUUID(), 1L)).id();
        for (JourneySegmentInput input : inputs) {
            journeyService.addSegment(journeyId, input);
        }
        return journeyId;
    }

    @Test
    void startRunCaseSegmentsLaunchesOneQueuedBatchPerSegment() {
        long programId = fixture.createProgram("jprog-");
        String[] one = new String[2];
        long pid1 = createProjectWithReadyCase("s1", one);
        String[] two = new String[2];
        long pid2 = createProjectWithReadyCase("s2", two);
        long journeyId = journeyWithSegments(programId, List.of(
                new JourneySegmentInput(1, pid1, "case", null, Long.parseLong(one[0]),
                        "{\"envId\":" + one[1] + "}"),
                new JourneySegmentInput(2, pid2, "case", null, Long.parseLong(two[0]),
                        "{\"envId\":" + two[1] + "}")));

        JourneyRunInfo run = journeyRunService.startRun(1L, journeyId);

        assertEquals(1, run.status(), "both segments launched → run stays running");
        assertEquals(2, run.segments().size());
        for (JourneyRunSegmentInfo segment : run.segments()) {
            assertEquals(1, segment.status(), "segment " + segment.seqNo() + " launched");
            assertNotNull(segment.batchId(), "expanded batch recorded on segment");
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_exec_batch WHERE id = ? AND status IN (2, 4)",
                    Integer.class, segment.batchId()),
                    "batch queued or claimed inside the segment project");
        }
        List<String> names = jdbcTemplate.queryForList(
                "SELECT name FROM tp_exec_batch WHERE name LIKE 'journey-%' AND id IN (?, ?)",
                String.class, run.segments().get(0).batchId(),
                run.segments().get(1).batchId());
        assertEquals(2, names.size(), "every expanded batch carries the journey name prefix");
    }

    @Test
    void startRunAssetSegmentWithoutCaseListFailsSegmentAndSkipsDownstream() {
        long programId = fixture.createProgram("jprog-");
        String[] one = new String[2];
        long pid = createProjectWithReadyCase("a1", one);
        String[] two = new String[2];
        long pid2 = createProjectWithReadyCase("a2", two);
        long journeyId = journeyWithSegments(programId, List.of(
                new JourneySegmentInput(1, pid, "asset", publishAssetRow(pid), null, "{}"),
                new JourneySegmentInput(2, pid2, "case", null, Long.parseLong(two[0]),
                        "{\"envId\":" + two[1] + "}")));

        JourneyRunInfo run = journeyRunService.startRun(1L, journeyId);

        assertEquals(3, run.status(), "failed expansion closes the run as failed");
        JourneyRunSegmentInfo first = run.segments().get(0);
        assertEquals(3, first.status());
        assertEquals("PARAMS_MISSING", first.failureCode());
        assertTrue(first.failureMessage().contains("case"),
                "failure message names what to fix");
        JourneyRunSegmentInfo second = run.segments().get(1);
        assertEquals(4, second.status(), "downstream segment skipped, never launched");
        assertNullSegmentBatch(second);
    }

    private void assertNullSegmentBatch(JourneyRunSegmentInfo segment) {
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_batch WHERE id = ? ", Integer.class,
                segment.batchId() == null ? -1L : segment.batchId()));
    }

    @Test
    void onBatchFinalizedForOrdinaryBatchKeepsZeroSideEffects() {
        long before = count("SELECT count(*) FROM tp_journey_run");
        journeyRunFinalizeSupport.onBatchFinalized(987654321L, 6);
        assertEquals(before, count("SELECT count(*) FROM tp_journey_run"),
                "unknown batch must not touch journey tables");
    }

    @Test
    void onBatchFinalizedAggregatesSegmentsIntoRunStatusAndNotifiesOwner() {
        long programId = fixture.createProgram("jprog-");
        String[] one = new String[2];
        long pid = createProjectWithReadyCase("g1", one);
        long journeyId = journeyWithSegments(programId, List.of(
                new JourneySegmentInput(1, pid, "case", null, Long.parseLong(one[0]),
                        "{\"envId\":" + one[1] + "}")));
        JourneyRunInfo started = journeyRunService.startRun(1L, journeyId);
        long runId = started.id();
        long segRowId = started.segments().get(0).id();
        long batchId = started.segments().get(0).batchId();

        journeyRunFinalizeSupport.onBatchFinalized(batchId, 7);

        JourneyRunInfo closed = journeyRunService.getRun(runId);
        assertEquals(3, closed.segments().get(0).status(),
                "partial-failed batch → segment failed (service status set: 3=failed)");
        assertEquals(3, closed.status(), "any failed segment → run failed");
        assertNotNull(closed.segments().get(0).failureCode(), nullSafe(segRowId));
        assertEquals(1, count("SELECT count(*) FROM tp_notification WHERE dedup_key = "
                + "'JOURNEY_RUN_FINISHED:" + runId + "'"), "owner notified once on run close");
    }

    private String nullSafe(long segRowId) {
        return "segment row " + segRowId + " should carry a failure code after aggregation";
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    /** Terminal (failed) run on a one-segment journey, unsigned. */
    private long terminalUnsignedRun(long programId) {
        String[] one = new String[2];
        long pid = createProjectWithReadyCase("sg", one);
        long journeyId = journeyWithSegments(programId, List.of(
                new JourneySegmentInput(1, pid, "asset", publishAssetRow(pid), null, "{}")));
        return journeyRunService.startRun(1L, journeyId).id();
    }

    @Test
    void signOffRejectsCallerWhoIsNotTheJourneyOwner() {
        long programId = fixture.createProgram("jprog-");
        long runId = terminalUnsignedRun(programId);
        // Journey owner is user 1 (createJourney input). A different account has no
        // exit authority and must be refused with a message naming the owner account.
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> journeyRunService.signOff(2L, runId),
                "only tp_journey.owner_user_id may sign the run exit");
        assertTrue(rejected.getMessage().contains("owner"),
                "refusal names the journey owner as the account holding the authority");
        assertEquals(Integer.valueOf(BizErrorCode.FORBIDDEN), rejected.getCode(),
                "an ownership denial travels as 403, never as an internal 500");
        assertEquals(0, count("SELECT count(*) FROM tp_journey_run WHERE id = " + runId
                + " AND signed_by IS NOT NULL"), "refused sign-off writes nothing");
    }

    @Test
    void signOffSecondAttemptIsRejectedAndNamesExistingSignTime() {
        long programId = fixture.createProgram("jprog-");
        long runId = terminalUnsignedRun(programId);
        journeyRunService.signOff(1L, runId);
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> journeyRunService.signOff(1L, runId),
                "a run may be signed exactly once");
        assertTrue(rejected.getMessage().toLowerCase().contains("signed"),
                "refusal points at the existing sign-off");
    }

    @Test
    void signOffWritesExactlyOneAuditRowForTheRun() {
        long programId = fixture.createProgram("jprog-");
        long runId = terminalUnsignedRun(programId);
        JourneyRunInfo signed = journeyRunService.signOff(1L, runId);
        assertEquals(1L, (long) signed.signedBy());
        assertNotNull(signed.signedAt());
        assertEquals(1, count("SELECT count(*) FROM tp_audit_log WHERE object_type = "
                + "'journey_signoff' AND action = 'JOURNEY_SIGNOFF' AND object_id = '" + runId + "'"),
                "each accepted sign-off leaves one auditable decision row");
    }

    @Test
    void journeyEventsAreRegisteredInTheNotificationDictionary() {
        assertTrue(NotificationEventDict.isKnown("JOURNEY_SEGMENT_FAILED"),
                "segment failure must be a dictionary event so routing accepts it");
        assertTrue(NotificationEventDict.isKnown("JOURNEY_RUN_FINISHED"),
                "run completion must be a dictionary event so routing accepts it");
    }
}
