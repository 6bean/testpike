package com.sixbean.testpike.core.batch.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.api.controller.BatchInternalController;
import com.sixbean.testpike.core.batch.api.dto.BatchCreateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.events.api.dto.TaskEventRow;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R62 B-face backend segment: the batch execution pipeline (start → claim →
 * result → finalize) now rides the shared process-event ledger under job kind
 * {@code batch-exec} with job_ref = run_token = the batch id, so the frontend
 * tail window can replay a running batch without any manual refresh. These
 * tests pin the whole real chain - no mocks: the frames must be produced by
 * the actual service calls, and the read door contract (cursor replay strictly
 * increasing, replay from the last cursor is empty, an unknown batch refuses
 * before the ledger is touched) mirrors the explore tail-door posture.
 *
 * <p>Fixture teardown is project-scoped (R37): every row hangs off the
 * batch id join while batch rows still exist.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {
        "testpike.scheduling.enabled=false",
        "testpike.prod-blacklist=*.prod-demo.com"})   // pin the blacklist shape (BatchTimeoutTest precedent)
class BatchExecTailLedgerTest {

    private static final String PROJECT_FAMILY_PREFIX = "批次尾窗";
    private static final String ACTION_JSON =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"http://demo.local/login\"}}]}";

    @Autowired
    private BatchInternalController door;
    @Autowired
    private BatchService batchService;
    @Autowired
    private ExecTaskService execTaskService;
    @Autowired
    private ExecutorService executorService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ProjectEnvService projectEnvService;
    @Autowired
    private ScriptService scriptService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanFixtureFamily() {
        List<Long> family = SharedDbFixtureSweep.projectIdsByNamePrefixes(
                jdbcTemplate, List.of(PROJECT_FAMILY_PREFIX));
        SharedDbFixtureSweep.batchCaseRowsOfProjects(jdbcTemplate, family);
        for (Long pid : family) {
            // The ledger keys rows by batch-id text (no FK into tp_project): join purge first.
            jdbcTemplate.update("DELETE FROM tp_ai_task_event WHERE job_kind = 'batch-exec'"
                            + " AND job_ref IN (SELECT id::text FROM tp_exec_batch WHERE project_id = ?)", pid);
        }
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'ex-tailb%'");
        // Default sweep covers tasks/batches/cases/scripts (+versions) and walks the FK
        // tree down to the project rows themselves (BatchTimeoutTest group-18 pattern).
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, family);
    }

    private long newFixtureProject() {
        String code = "TAILB-" + UUID.randomUUID().toString().substring(0, 8);
        return projectService.create(1L, new ProjectCreateInput(
                PROJECT_FAMILY_PREFIX + code, code, "zh-CN", "P1", null));
    }

    private long newEnv(long pid) {
        return projectEnvService.create(pid, new ProjectEnvInput("尾窗环境", 2,
                "https://tail-" + pid + ".example.com", null, "web", "chromium", "v1.0.0"));
    }

    /** Approved + scripted case so precheck accepts it into the task set. */
    private long newReadyCase(long pid, String title) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(pid, title, "前置", "预期",
                1, 2, "web", 3, 2, 1, null, List.of()));
        jdbcTemplate.update("UPDATE tp_test_case SET status = 3 WHERE id = ?", info.id());
        scriptService.save(new ScriptSaveInput(info.id(), "v2", ACTION_JSON, 2));
        return info.id();
    }

    private BatchInfo startedBatch(long pid, long env, int caseCount) {
        List<Long> caseIds = new java.util.ArrayList<>();
        for (int i = 1; i <= caseCount; i++) {
            caseIds.add(newReadyCase(pid, "尾窗用例" + i));
        }
        BatchInfo batch = batchService.create(1L,
                new BatchCreateInput(pid, "尾窗批次", env, caseIds, null));
        batchService.start(1L, batch.id());
        return batch;
    }

    private List<TaskEventRow> tail(long batchId, long cursor) {
        return door.processEvents(batchId, cursor, null).getData();
    }

    @Test
    void kickoffFrameIsWrittenByStartAndReplayedThroughTheCursorDoor() {
        long pid = newFixtureProject();
        BatchInfo batch = startedBatch(pid, newEnv(pid), 2);

        List<TaskEventRow> window = tail(batch.id(), 0);
        assertTrue(window.stream().anyMatch(r -> r.summary().startsWith("批次执行已发起")),
                "start must record the kickoff frame in the shared ledger, got " + window);
        assertTrue(window.stream().allMatch(r ->
                        "step".equals(r.level()) || "error".equals(r.level())),
                "the door only serves ledger rows with a known level");

        long lastCursor = window.get(window.size() - 1).cursor();
        assertTrue(tail(batch.id(), lastCursor).isEmpty(),
                "replay from the last cursor is empty (strictly increasing cursor contract)");
        long firstCursor = window.get(0).cursor();
        List<TaskEventRow> fromFirst = tail(batch.id(), firstCursor);
        assertEquals(window.size() - 1, fromFirst.size(),
                "cursor paging is idempotent: rows after the first cursor only");
        for (int i = 1; i < window.size(); i++) {
            assertTrue(window.get(i).cursor() > window.get(i - 1).cursor(),
                    "ledger rows arrive in strictly increasing cursor order");
        }
    }

    @Test
    void dispatchAndTerminalFramesFollowTheRealClaimResultChain() {
        long pid = newFixtureProject();
        BatchInfo batch = startedBatch(pid, newEnv(pid), 1);
        long poolTagId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_tag_dict (project_id, name, type) VALUES (?, '尾窗资源池', 7) RETURNING id
                """, Long.class, pid);
        long executorId = executorService.register(new ExecutorRegisterInput(
                "ex-tailb-" + poolTagId, String.valueOf(poolTagId), null, "web", "chromium", 2, "1.0.0", 0));

        ClaimTaskView claimed = execTaskService.claim(executorId);
        assertTrue(claimed != null, "the pinned-pool executor must claim the queued task");
        List<TaskEventRow> afterDispatch = tail(batch.id(), 0);
        assertTrue(afterDispatch.stream().anyMatch(r -> r.summary().startsWith("用例已派发")),
                "claim records the dispatch frame, got " + afterDispatch);

        execTaskService.submitResult(new ExecResultInput(claimed.taskId(), claimed.claimToken(),
                "fail", 2, "断言不通过", List.of(), List.of(), 1200, null,
                null, null, null, null, null));

        List<TaskEventRow> afterResult = tail(batch.id(), 0);
        assertTrue(afterResult.stream().anyMatch(r ->
                        "error".equals(r.level()) && r.summary().startsWith("用例执行失败")),
                "a failed result writes an error-level case terminal frame");
        assertTrue(afterResult.stream().anyMatch(r -> r.summary().startsWith("批次执行结束")),
                "finalizing the batch writes the sealed frame (no manual refresh needed)");
        assertEquals(7, batchService.get(batch.id()).status(),
                "the only case failed → batch partial-failed(7), ledger tail agrees with row state");
    }

    @Test
    void cancellingABatchWritesTheSealedTerminalFrame() {
        long pid = newFixtureProject();
        BatchInfo batch = startedBatch(pid, newEnv(pid), 1);

        batchService.cancel(1L, batch.id());

        List<TaskEventRow> afterCancel = tail(batch.id(), 0);
        assertTrue(afterCancel.stream().anyMatch(r ->
                        r.summary().startsWith("批次执行结束｜批次已取消")),
                "the cancel door must seal the tail (terminal prefix family) so the window "
                        + "collapses without riding the poll budget, got " + afterCancel);
        assertEquals(8, batchService.get(batch.id()).status(),
                "ledger terminal agrees with the cancelled row state");
    }

    @Test
    void unknownBatchRefusesBeforeTheLedgerIsRead() {
        long missing = 999_999_901L;
        ServiceException refusal = assertThrows(ServiceException.class,
                () -> door.processEvents(missing, 0, null));
        assertEquals(40401, refusal.getCode(),
                "an unknown batch id refuses with the honest not-found code before the ledger is touched");
    }
}
