package com.sixbean.testpike.core.ai.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.ai.api.dto.AiBatchCreateInput;
import com.sixbean.testpike.core.ai.api.dto.AiBatchCreateResult;
import com.sixbean.testpike.core.ai.api.dto.AiBatchInfo;
import com.sixbean.testpike.core.ai.api.dto.AiTaskCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Stop door of an AI generation task : the process tail drawer can
 * only offer a real "stop" once the task ledger itself can be settled by a
 * user request. Pinned behaviours: a live task (queued or running) moves to
 * the cancelled state, an already settled task honestly rejects the stop
 * instead of silently rewriting history, a repeated stop is idempotent, an
 * unknown id answers not-found, and a cancelled task refuses every late
 * status writeback so nothing a stopped run produced can be attached
 * afterwards. The batch progress door counts stopped items in their own
 * bucket, so a cancelled sub task never stays in the queued bucket and the
 * progress line can settle. Real slot database, real service doors; fixture
 * rows are self-created and swept by id (R37: shipped tables are never
 * TRUNCATEd).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiTaskCancelDoorTest {

    /** Cancelled is the fifth status of the generation task ledger. */
    private static final int STATUS_CANCELLED = 5;

    @Autowired
    private AiTaskService aiTaskService;

    @Autowired
    private AiBatchService aiBatchService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.List<Long> taskIds = new java.util.ArrayList<>();
    private final java.util.List<Long> batchIds = new java.util.ArrayList<>();
    private final java.util.List<Long> projectIds = new java.util.ArrayList<>();

    @AfterEach
    void cleanUpFixtures() {
        for (Long batchId : batchIds) {
            // Sub tasks of a fixture batch are created by the batch door, so
            // they are swept through the batch anchor before the batch row.
            // job_ref stores the task id as text, so the subquery casts it
            // explicitly — PostgreSQL has no implicit varchar = bigint operator.
            jdbcTemplate.update("DELETE FROM tp_ai_task_event WHERE job_ref IN "
                    + "(SELECT CAST(id AS VARCHAR) FROM tp_ai_task WHERE batch_id = ?)", batchId);
            jdbcTemplate.update("DELETE FROM tp_ai_task WHERE batch_id = ?", batchId);
            jdbcTemplate.update("DELETE FROM tp_ai_batch WHERE id = ?", batchId);
        }
        for (Long taskId : taskIds) {
            jdbcTemplate.update("DELETE FROM tp_ai_task_event WHERE job_ref = ?", String.valueOf(taskId));
            jdbcTemplate.update("DELETE FROM tp_ai_task WHERE id = ?", taskId);
        }
        for (Long project : projectIds) {
            // The membership row references the fixture project, so it goes first.
            jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", project);
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", project);
        }
        batchIds.clear();
        taskIds.clear();
        projectIds.clear();
    }

    private long project() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        long id = projectService.create(1L,
                new ProjectCreateInput("停止门项目" + code, code, "zh-CN", "P1", null));
        projectIds.add(id);
        return id;
    }

    /** Creates a generation task through the service door and pins its row to the given status. */
    private long taskInStatus(long project, int status) {
        long id = aiTaskService.create(new AiTaskCreateInput(project, 1L, 1,
                "停止门夹具需求 " + UUID.randomUUID().toString().substring(0, 8), null));
        taskIds.add(id);
        jdbcTemplate.update("UPDATE tp_ai_task SET status = ? WHERE id = ?", status, id);
        return id;
    }

    private int statusOf(long taskId) {
        return jdbcTemplate.queryForObject("SELECT status FROM tp_ai_task WHERE id = ?",
                Integer.class, taskId);
    }

    @Test
    void stopsALiveTaskAndSettlesItAsCancelled() {
        long project = project();
        long queued = taskInStatus(project, 1);
        aiTaskService.cancelTask(queued);
        assertEquals(STATUS_CANCELLED, statusOf(queued), "a queued task must stop as cancelled");

        long running = taskInStatus(project, 2);
        aiTaskService.cancelTask(running);
        assertEquals(STATUS_CANCELLED, statusOf(running), "a running task must stop as cancelled");
    }

    @Test
    void repeatedStopIsIdempotentAndKeepsTheFirstResult() {
        long taskId = taskInStatus(project(), 2);
        aiTaskService.cancelTask(taskId);
        aiTaskService.cancelTask(taskId);
        assertEquals(STATUS_CANCELLED, statusOf(taskId), "a repeated stop must not move the row again");
    }

    @Test
    void refusesToStopAnAlreadySettledTask() {
        long project = project();
        long done = taskInStatus(project, 3);
        ServiceException doneReject = assertThrows(ServiceException.class,
                () -> aiTaskService.cancelTask(done));
        assertEquals(BizErrorCode.STATE_MACHINE_REJECT, doneReject.getCode().intValue(),
                "a finished task answers a state-machine rejection, not a silent rewrite");

        long failed = taskInStatus(project, 4);
        assertThrows(ServiceException.class, () -> aiTaskService.cancelTask(failed));
        assertEquals(4, statusOf(failed), "the rejection must leave the settled row untouched");
    }

    @Test
    void answersNotFoundForAnUnknownTask() {
        ServiceException missing = assertThrows(ServiceException.class,
                () -> aiTaskService.cancelTask(999_999_999L));
        assertEquals(BizErrorCode.NOT_FOUND, missing.getCode().intValue(), "unknown id answers not-found");
    }

    @Test
    void lateWritebackCannotResurrectAStoppedTask() {
        long taskId = taskInStatus(project(), 2);
        aiTaskService.cancelTask(taskId);

        assertThrows(ServiceException.class, () -> aiTaskService.markRunning(taskId),
                "a redelivered start must be rejected after the stop");
        assertThrows(ServiceException.class, () -> aiTaskService.markDone(taskId, 4242L),
                "a generated case must never attach to a stopped task");
        assertThrows(ServiceException.class, () -> aiTaskService.markFailed(taskId, "SERVICE_ERROR", "late failure"),
                "a late failure must not rewrite the stopped row");
        assertEquals(STATUS_CANCELLED, statusOf(taskId));
        assertNull(jdbcTemplate.queryForObject("SELECT case_id FROM tp_ai_task WHERE id = ?",
                Long.class, taskId), "no result may be written back to a stopped task");
    }

    @Test
    void batchProgressCountsStoppedItemsInTheirOwnBucket() {
        long project = project();
        AiBatchCreateResult created = aiBatchService.createBatch(AiBatchCreateInput.fromTexts(
                project, 1L, List.of("stopped item", "still queued item")));
        batchIds.add(created.batchId());
        List<Long> items = created.taskIds();
        assertEquals(2, items.size(), "fixture batch has two sub tasks");

        aiTaskService.cancelTask(items.get(0));
        AiBatchInfo batch = aiBatchService.get(created.batchId());
        assertEquals(1, batch.cancelled(), "the stopped item is accounted as cancelled");
        assertEquals(1, batch.pending(), "the other item stays queued");
        assertEquals(0, batch.running(), "no item is running");
        assertEquals(STATUS_CANCELLED, batch.items().get(0).status(),
                "the item detail exposes the stopped status");
    }
}
