package com.sixbean.testpike.core.mock.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.events.api.dto.TaskEventRow;
import com.sixbean.testpike.core.events.domain.component.AiTaskEventTrimSweeper;
import com.sixbean.testpike.core.events.domain.component.TaskEventWriter;
import com.sixbean.testpike.core.mock.api.dto.MockAiJobCreateInput;
import com.sixbean.testpike.core.mock.api.dto.MockAiJobInfo;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.domain.service.MockSpaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R62 read door of the bulk contract-enrichment tail: the core
 * internal controller replays the business-step frames of one drafting job
 * through the shared process ledger, resolving the newest run anchor itself
 * so a re-run automatically tails its latest attempt with no client-side
 * token. These tests pin the door against the real ledger (zero mocks):
 * replay rides the latest run only, the cursor window is strictly increasing
 * and idempotent, an unknown job refuses with honest wording before any
 * ledger row is read, and a job whose run has not landed its first frame
 * honestly answers an empty window instead of an error.
 */
@SpringBootTest(classes = CoreApplication.class)
class MockEnrichTailReadDoorTest {

    private static final String MASTER_KEY = "ai.mock.enabled";
    private static final String NL_STUB_KEY = "ai.mock.nl-stub";
    private static final String ENRICH_KIND = AiTaskEventTrimSweeper.KIND_MOCK_SPEC_ENRICH;

    @Autowired
    private MockAiJobInternalController door;

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private TaskEventWriter taskEvents;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long spaceId;
    private final List<String> tailRefs = new ArrayList<>();

    @BeforeEach
    void openGatesAndSeedSpace() {
        String code = "enrichtail" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        projectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
        spaceId = spaceService.create(projectId,
                new MockSpaceInput("Enrich tail space", code, null, null,
                        "prefix", "none", null, null, 100, null));
        configCacheHolder.put(MASTER_KEY, "1");
        configCacheHolder.put(NL_STUB_KEY, "1");
    }

    @AfterEach
    void restoreGatesAndCleanFixture() {
        for (String key : new String[]{MASTER_KEY, NL_STUB_KEY}) {
            configCacheHolder.remove(key);
        }
        if (!tailRefs.isEmpty()) {
            jdbcTemplate.update("DELETE FROM tp_ai_task_event WHERE job_kind = ? AND job_ref IN ("
                            + String.join(",", java.util.Collections.nCopies(tailRefs.size(), "?")) + ")",
                    concatRefs());
        }
        jdbcTemplate.update("DELETE FROM tp_mock_ai_job WHERE space_id = ?", spaceId);
        jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        RequestContextHolder.resetRequestAttributes();
    }

    private Object[] concatRefs() {
        Object[] args = new Object[tailRefs.size() + 1];
        args[0] = ENRICH_KIND;
        for (int i = 0; i < tailRefs.size(); i++) {
            args[i + 1] = tailRefs.get(i);
        }
        return args;
    }

    /** File a drafting job through the real create door (gates already open). */
    private long newJob() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(OperatorContext.OPERATOR_ID_HEADER, "7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        Response<MockAiJobInfo> resp = door.create(
                new MockAiJobCreateInput(spaceId, "nl-stub", "tail door fixture job"));
        RequestContextHolder.resetRequestAttributes();
        long jobId = resp.getData().id();
        tailRefs.add(String.valueOf(jobId));
        return jobId;
    }

    private void writeRun(long jobId, String runToken, String... summaries) {
        String ref = String.valueOf(jobId);
        for (String summary : summaries) {
            taskEvents.step(ENRICH_KIND, ref, runToken, summary, null);
        }
    }

    private List<TaskEventRow> tail(long jobId, long cursor) {
        return door.processEvents(jobId, cursor, null).getData();
    }

    @Test
    void replayRidesTheLatestRunAndKeepsTheCursorWindowIdempotent() {
        long jobId = newJob();
        writeRun(jobId, "run-1", "old attempt started", "old attempt step");
        writeRun(jobId, "run-2", "enrichment started", "contract read", "suggestions generated");

        List<TaskEventRow> window = tail(jobId, 0);
        assertEquals(3, window.size(),
                "the door tails the newest attempt only; stale runs never mix in");
        assertTrue(window.stream().allMatch(r -> r.summary().contains("enrich")
                        || r.summary().contains("contract") || r.summary().contains("suggestions")),
                "every replayed row belongs to the latest run token");
        for (int i = 1; i < window.size(); i++) {
            assertTrue(window.get(i).cursor() > window.get(i - 1).cursor(),
                    "cursors are strictly increasing so the viewer can resume");
        }

        long lastCursor = window.get(window.size() - 1).cursor();
        assertTrue(tail(jobId, lastCursor).isEmpty(),
                "replaying the last seen cursor lands no new frames (idempotent resume)");

        long firstCursor = window.get(0).cursor();
        assertEquals(2, tail(jobId, firstCursor).size(),
                "a mid cursor answers exactly the window after it, no repeats, no skips");
    }

    @Test
    void unknownJobRefusesBeforeTheLedgerIsTouched() {
        long missing = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 777777 FROM tp_mock_ai_job", Long.class);
        // Ghost frames ride the ledger for an id no job owns: the refusal must
        // come from the job check, never from a window built on a phantom job.
        writeRun(missing, "ghost-run", "frame of a job that does not exist");
        tailRefs.add(String.valueOf(missing));

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> door.processEvents(missing, 0, null));
        assertTrue(refusal.getMessage() != null && !refusal.getMessage().isBlank(),
                "an unknown job answers honest not-found wording, not an empty silent window");
    }

    @Test
    void aJobWithoutFramesYetHonestlyAnswersAnEmptyWindow() {
        long jobId = newJob();
        assertTrue(tail(jobId, 0).isEmpty(),
                "before the first frame lands the window is empty — a valid answer, never an error");
    }
}
