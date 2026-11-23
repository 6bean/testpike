package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.common.domain.component.AfterCommitExecutor;
import com.sixbean.testpike.core.journey.domain.component.JourneyRunFinalizeSupport;
import com.sixbean.testpike.core.events.api.dto.CaseEventMessage;
import com.sixbean.testpike.core.events.api.dto.CaseEventType;
import com.sixbean.testpike.core.events.domain.service.CaseEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * <ol>
 * </ol>
 *
 */
@Component
public class BatchFinalizeSupport {

 
    private static final int BATCH_RUNNING = 4;
    private static final int BATCH_PAUSED = 5;
 
    private static final int BATCH_DONE = 6;
    private static final int BATCH_PARTIAL_FAILED = 7;
 
    private static final int TASK_PENDING = 1;
    private static final int TASK_RUNNING = 2;
    
    private static final int TASK_FAILED = 4;
    private static final int TASK_TIMEOUT = 5;
 
    private static final int RESULT_PASS = 1;
    private static final int RESULT_FAIL = 2;
    private static final int RESULT_SKIP = 3;

    private final JdbcTemplate jdbcTemplate;
    private final EnvExecutionLock envExecutionLock;
    private final BatchFailureNotifier batchFailureNotifier;
    private final AutoDefectSupport autoDefectSupport;
    private final BatchReportSupport batchReportSupport;
    private final BatchFinishedNotifier batchFinishedNotifier;
    private final AfterCommitExecutor afterCommitExecutor;
    private final AppAutoBatchCleanSupport appAutoBatchCleanSupport;
    private final CaseEventPublisher caseEventPublisher;
    private final JourneyRunFinalizeSupport journeyRunFinalizeSupport;
    /** R62 B-face: shared process-event ledger writer (single source, no second channel). */
    private final com.sixbean.testpike.core.events.domain.component.TaskEventWriter taskEvents;

    public BatchFinalizeSupport(JdbcTemplate jdbcTemplate, EnvExecutionLock envExecutionLock,
                                BatchFailureNotifier batchFailureNotifier,
                                AutoDefectSupport autoDefectSupport,
                                BatchReportSupport batchReportSupport,
                                BatchFinishedNotifier batchFinishedNotifier,
                                AfterCommitExecutor afterCommitExecutor,
                                @org.springframework.context.annotation.Lazy
                                AppAutoBatchCleanSupport appAutoBatchCleanSupport,
                                CaseEventPublisher caseEventPublisher,
                                @org.springframework.context.annotation.Lazy
                                JourneyRunFinalizeSupport journeyRunFinalizeSupport,
                                com.sixbean.testpike.core.events.domain.component.TaskEventWriter taskEvents) {
        this.jdbcTemplate = jdbcTemplate;
        this.envExecutionLock = envExecutionLock;
        this.batchFailureNotifier = batchFailureNotifier;
        this.autoDefectSupport = autoDefectSupport;
        this.batchReportSupport = batchReportSupport;
        this.batchFinishedNotifier = batchFinishedNotifier;
        this.afterCommitExecutor = afterCommitExecutor;
        this.appAutoBatchCleanSupport = appAutoBatchCleanSupport;
        this.caseEventPublisher = caseEventPublisher;
        this.journeyRunFinalizeSupport = journeyRunFinalizeSupport;
        this.taskEvents = taskEvents;
    }

    
    public void onTaskTerminal(ExecTaskInfo task, String result, Integer errorType, Integer durationMs) {
        Long batchId = jdbcTemplate.query(
                "SELECT batch_id FROM tp_exec_task WHERE id = ?",
                (org.springframework.jdbc.core.ResultSetExtractor<Long>) rs ->
                        rs.next() ? (Long) rs.getObject(1) : null,
                task.id());
        if (batchId == null) {
            return;
        }
        backfillCaseResult(batchId, task.caseId(), result, errorType, durationMs);
        recordCaseTerminalFrame(batchId, task, result, durationMs);
        finalizeIfAllTerminal(batchId);
    }

    // ── R62 B-face tail frames (shared tp_ai_task_event ledger, kind batch-exec,
    //    job_ref = run_token = batch id; single writer door TaskEventWriter) ──

    /** Kickoff frame written by the start pipeline once the batch row is queued. */
    public void recordStartFrame(long batchId, int createdTasks) {
        tailStep(batchId, "批次执行已发起｜共 " + createdTasks + " 条用例进入执行队列",
                "batchId=" + batchId + " tasks=" + createdTasks);
    }

    /** Dispatch frame: a queued task moved onto an executor at claim time. */
    public void recordDispatchFrame(long batchId, long taskId, long caseId, long executorId) {
        tailStep(batchId, "用例已派发｜任务 " + taskId + "（用例 " + caseId
                        + "）由执行器 " + executorId + " 领取",
                "batchId=" + batchId + " taskId=" + taskId + " executorId=" + executorId);
    }

    /** Case terminal frame from the result callback (error level for failures). */
    private void recordCaseTerminalFrame(long batchId, ExecTaskInfo task,
                                         String result, Integer durationMs) {
        String summary = switch (result) {
            case "pass" -> "用例执行通过｜任务 " + task.id()
                    + (durationMs == null ? "" : "｜耗时 " + durationMs + "ms");
            case "fail" -> "用例执行失败｜任务 " + task.id()
                    + (durationMs == null ? "" : "｜耗时 " + durationMs + "ms");
            case "manual_skip" -> "用例已跳过｜任务 " + task.id();
            default -> "用例已取消｜任务 " + task.id();
        };
        String detail = "batchId=" + batchId + " taskId=" + task.id() + " result=" + result;
        if ("fail".equals(result)) {
            taskEvents.error(com.sixbean.testpike.core.events.domain.component
                    .AiTaskEventTrimSweeper.KIND_BATCH_EXEC,
                    String.valueOf(batchId), String.valueOf(batchId), summary, detail);
        } else {
            tailStep(batchId, summary, detail);
        }
    }

    /** Sealed frame when the shared segment advances the batch to its terminal state. */
    private void recordSealedFrame(long batchId, int targetStatus) {
        tailStep(batchId, targetStatus == BATCH_DONE
                        ? "批次执行结束｜全部用例通过"
                        : "批次执行结束｜存在失败用例，明细见批次报告",
                "batchId=" + batchId + " status=" + targetStatus);
    }

    /**
     * Cancel-door seal (R62③): cancellation lands the terminal state outside the
     * finalize guard (the batch row is already 8 when the last tasks are dequeued),
     * so without this frame the tail window would ride its poll budget to the
     * honest-interrupted degrade instead of collapsing on a terminal frame.
     */
    public void recordCancelledFrame(long batchId, int releasedTasks) {
        tailStep(batchId, "批次执行结束｜批次已取消（" + releasedTasks
                        + " 个未派发任务出队，已完成结果保留）",
                "batchId=" + batchId + " status=8 released=" + releasedTasks);
    }

    /** Watchdog seal: the batch-timeout sweep cancels without any other trigger source. */
    public void recordWatchdogSealedFrame(long batchId, int releasedTasks) {
        tailStep(batchId, "批次执行结束｜超时看门狗自动取消（" + releasedTasks
                        + " 个未派发任务出队）",
                "batchId=" + batchId + " status=8 watchdog=1 released=" + releasedTasks);
    }

    private void tailStep(long batchId, String summary, String detail) {
        taskEvents.step(com.sixbean.testpike.core.events.domain.component
                        .AiTaskEventTrimSweeper.KIND_BATCH_EXEC,
                String.valueOf(batchId), String.valueOf(batchId), summary, detail);
    }

    
    public void onCaseSkipped(long batchId) {
        finalizeIfAllTerminal(batchId);
    }

    /**
 * <p> Migration component reuse: cancellation (7) is also a terminal-state advancement without a callback trigger source because the core has already set the terminal state before the executor kills.
 * Late callbacks take the idempotent return and no longer trigger onTaskTerminal, and this segment prevents dangling batch 4 with the same name and semantics.
 * Do not start a separate implementation, which is forbidden by red line 6.
 */
    public void onTasksTimedOut(java.util.Collection<Long> batchIds) {
        for (Long batchId : batchIds) {
            finalizeIfAllTerminal(batchId);
        }
    }

    /** Completion determination and batch advancement form a shared segment for two trigger paths: do not finalize early if non-terminal tasks remain, and advance to 6/7 according to has_fail when all tasks are terminal. */
    private void finalizeIfAllTerminal(long batchId) {
        Long unfinished = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE batch_id = ? AND status IN (?, ?)",
                Long.class, batchId, TASK_PENDING, TASK_RUNNING);
        if (unfinished != null && unfinished > 0) {
            return;   // If undispatched or running tasks remain, do not finalize early.
        }
        Long failed = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE batch_id = ? AND status IN (?, ?)",
                Long.class, batchId, TASK_FAILED, TASK_TIMEOUT);
        int target = failed != null && failed > 0 ? BATCH_PARTIAL_FAILED : BATCH_DONE;
        int advanced = jdbcTemplate.update("UPDATE tp_exec_batch SET status = ?, finished_at = now(), "
                        + "version = version + 1, updated_at = now() WHERE id = ? AND status IN (?, ?)",
                target, batchId, BATCH_RUNNING, BATCH_PAUSED);
 // ⑤ / : release the environment mutex lock at terminal state, and release only the batch actually advanced this time to prevent concurrent double triggering.
 // Mistakenly releasing a lock just acquired by another batch is prevented, and a conditional update affecting 0 rows means another party has already acted with zero side effects.
        if (advanced > 0) {
            recordSealedFrame(batchId, target);
            envExecutionLock.releaseByBatch(batchId);
 // M8 I7: persist the point-in-time snapshot report row first atomically in the same transaction, and the notification path reads that row from the same source without recalculating.
            // Both 6/7 are written, and the full batch-completion record includes partial failures.
            batchReportSupport.writeSnapshot(batchId, target);
            batchFinishedNotifier.notifyFinished(batchId, target);
// Proactive-insights trigger: batch-finalized domain event (the publisher wraps the send
// in afterCommit itself, so a rolled-back finalize never emits). Payload batchId + targetStatus
// let the consumer cluster failure root causes per project segment; projectId is read from the row.
            publishBatchFinalizedEvent(batchId, target);
 // In the second half, partial failure (7) routes the BATCH_FAILED in-site message to test_owner.
            // Similarly, it is sent only when this batch is actually advanced, shares the idempotent guard with lock release, and AfterCommitExecutor does not send on rollback.
            if (target == BATCH_PARTIAL_FAILED) {
                batchFailureNotifier.notifyPartialFailed(batchId);
 // M7 / V3 (02): hook automatic ticket creation at the same guard point, internally catch all exceptions, and only warn.
 // Do not block the main chain; red line 6 forbids a second chain, making this the only shared-segment point.
                autoDefectSupport.onBatchPartialFailed(batchId);
            }
 // Automatic batch-run cleanup: dispatch at the same guard point via AfterCommit, act only after commit, and do not dispatch dangling-batch cleanup on rollback.
            // If the flag is off, there is no occupied transaction, or dispatch is rejected, take zero action or leave a human-readable trace, internally catch all exceptions, and forbid a red batch.
            // @Lazy breaks the ExecTaskServiceImpl -> this component -> cleanup component -> ExecTaskService construction cycle, following the precedent in the same document.
            afterCommitExecutor.execute(() -> appAutoBatchCleanSupport.onBatchFinalized(batchId));
            // Journey run aggregation: same afterCommit guard, in-process single path (R40 —
            // no second MQ consumer). A batch outside any journey costs one indexed lookup.
            afterCommitExecutor.execute(
                    () -> journeyRunFinalizeSupport.onBatchFinalized(batchId, target));
        }
    }

    /**
 * Emit the batch-finalized domain event (proactive-insights trigger).
 * projectId is read back from the batch row so the consumer can enforce the project
 * segment (H1 isolation); a missing row (defensive) skips the publish instead of
 * emitting a null-project payload.
 */
    private void publishBatchFinalizedEvent(long batchId, int targetStatus) {
        Long projectId = jdbcTemplate.query(
                "SELECT project_id FROM tp_exec_batch WHERE id = ?",
                (org.springframework.jdbc.core.ResultSetExtractor<Long>) rs ->
                        rs.next() ? (Long) rs.getObject(1) : null,
                batchId);
        if (projectId == null) {
            return;
        }
        caseEventPublisher.publish(new CaseEventMessage(
                CaseEventType.EXEC_BATCH_FINALIZED.name(), null, projectId, null,
                null, null, null, null, null, System.currentTimeMillis(), null,
                null, null, null, null, batchId, targetStatus));
    }

 /** Backfill the detail result at four levels (01), and ensure that server-skipped rows (manual_skip=1) are not overwritten by late callbacks. */
    private void backfillCaseResult(long batchId, long caseId, String result,
                                    Integer errorType, Integer durationMs) {
        switch (result) {
            case "pass" -> jdbcTemplate.update(
                    "UPDATE tp_batch_case SET result = ?, error_type = ?, duration_ms = ?"
                            + " WHERE batch_id = ? AND case_id = ? AND manual_skip = 0",
                    RESULT_PASS, errorType, durationMs, batchId, caseId);
            case "fail" -> jdbcTemplate.update(
                    "UPDATE tp_batch_case SET result = ?, error_type = ?, duration_ms = ?"
                            + " WHERE batch_id = ? AND case_id = ? AND manual_skip = 0",
                    RESULT_FAIL, errorType, durationMs, batchId, caseId);
            case "manual_skip" -> jdbcTemplate.update(
                    "UPDATE tp_batch_case SET result = ?, manual_skip = 1, error_type = ?,"
                            + " duration_ms = ? WHERE batch_id = ? AND case_id = ?",
                    RESULT_SKIP, errorType, durationMs, batchId, caseId);
            default -> {   // submitResult has already been pre-validated, so this branch is defensive with zero side effects.
            }
        }
    }
}
