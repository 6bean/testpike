package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.executor.api.dto.BatchCancelResult;
import com.sixbean.testpike.core.executor.api.dto.BatchCaseRerunResult;
import com.sixbean.testpike.core.executor.api.dto.CaseExecHistoryView;
import com.sixbean.testpike.core.executor.api.dto.ParallelWaterLevelView;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecStepEvidenceView;
import com.sixbean.testpike.core.executor.api.dto.ExecStepSnapshotView;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.executor.api.dto.SessionArtifactsInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorTaskPageView;
import com.sixbean.testpike.core.executor.api.dto.RunningTaskView;
import com.sixbean.testpike.core.executor.api.dto.ScreenshotView;

import java.util.List;
import java.util.Map;


public interface ExecTaskService {

 
    long create(ExecTaskCreateInput input);

    
    long createToolTestRun(long projectId, String actionJson);

    
    long createExploreAct(long projectId, String actionJson);

    
    long createApiTestRun(long projectId, String actionJson);

    
    long createPerfRunTask(long projectId, String actionJson);

    
    long createAppAutoRun(long projectId, String actionJson);

    
    long createAppAutoRun(long projectId, long caseId, Long batchId, String actionJson);

    
    void timeoutToolRun(long taskId);

    
    ClaimTaskView claim(long executorId);

    
    ParallelWaterLevelView parallelWaterLevel();

    
    void submitResult(ExecResultInput input);

    
    void ingestSessionArtifacts(SessionArtifactsInput input);

    
    ExecTaskInfo get(long taskId);

    
    List<ExecTaskInfo> listByIds(List<Long> taskIds);

    
    default List<ExecTaskInfo> listByProject(long projectId, int page, int size) {
        return listByProject(projectId, page, size, null, null);
    }

    /**
 * Paged read with an optional whitelisted sort .
 * sortBy/sortDir null or blank keeps the historical id DESC order,
 * so every existing caller sees the untouched default without changes.
 */
    default List<ExecTaskInfo> listByProject(long projectId, int page, int size,
                                             String sortBy, String sortDir) {
        return listByProject(projectId, page, size, sortBy, sortDir, null, null);
    }

    /**
     * Paged read narrowed by an optional batch id and a comma separated
     * status whitelist (AW2 batch-promotion picker feeds its task table
     * from one batch's failed/timeout rows). An off-whitelist status code
     * is refused fail-closed with a human-readable message — the whitelist
     * is a core decision, callers never re-implement it.
     */
    List<ExecTaskInfo> listByProject(long projectId, int page, int size,
                                     String sortBy, String sortDir,
                                     Long batchId, String statusIn);

    
    default long countByProject(long projectId) {
        return countByProject(projectId, null, null);
    }

    /** Count matching the same optional narrowing pair as {@link #listByProject}. */
    long countByProject(long projectId, Long batchId, String statusIn);

    
    List<ScreenshotView> listScreenshots(long taskId);

    
    String screenshotViewUrl(long taskId, String objectKey);

    
    List<ExecStepSnapshotView> listStepSnapshots(long taskId);

    
    List<ExecStepEvidenceView> listStepEvidence(long taskId);

    
    PageResult<CaseExecHistoryView> listHistoryByCase(long caseId, long projectId, int page, int size);

    /**
 * <p>4-arg form keeps the historical id DESC order; the 6-arg overload below
 * adds an optional whitelisted sort pair .
 */
    default ExecutorTaskPageView listByExecutor(long executorId, String statusGroup, int page, int size) {
        return listByExecutor(executorId, statusGroup, page, size, null, null);
    }

    /**
 * Paged read with an optional whitelisted sort .
 * sortBy/sortDir null or blank keeps the historical id DESC order, and the
 * pending branch (global queue) shares the same whitelist through the one
 * ORDER BY builder — no second sorting path.
 */
    ExecutorTaskPageView listByExecutor(long executorId, String statusGroup, int page, int size,
                                        String sortBy, String sortDir);

 
    String getLog(long taskId);

    
    int sweepTimeoutTasks();

    
    int redistributeTasksOfOfflineExecutors();

    
    int redistributeTasksOfBatch(long batchId);

    /**
     * Count this batch's tasks that the automatic offline sweep already reclaimed and
     * that are still sitting in the pending queue (status=1, last_reclaim_source=1).
     * Used by manual batch recovery to reconcile counts truthfully when the 15s sweep
     * wins the race against the operator's click.
     */
    int countSweepReclaimedOfBatch(long batchId);

    
    BatchCaseRerunResult rerunBatchCase(long taskId, Long operatorId, String engineCode);

    
    void cancel(long operatorId, long taskId);

    
    BatchCancelResult batchCancel(long operatorId, List<Long> taskIds);

    
    List<Long> pendingCancelsFor(long executorId);

    /**
 * Running-process page : cross-batch status=2 window
 * (case / batch / executor / started / elapsed / budget=claim-timeout shared source).
 * <p>2-arg form keeps the historical id ASC order; the overload below adds an
 * optional whitelisted sort pair.
 */
    default PageResult<RunningTaskView> listRunning(int page, int size) {
        return listRunning(page, size, null, null);
    }

    /**
 * Running window with an optional whitelisted sort .
 * sortBy/sortDir null or blank keeps the historical id ASC order; the
 * elapsed_ms key orders by the same computed alias the row renders, and an
 * off-list key is refused fail-closed by the shared whitelist gate.
 */
    PageResult<RunningTaskView> listRunning(int page, int size, String sortBy, String sortDir);

    
    void updateAiClassify(long taskId, Integer aiFailureClass, String aiFailureNote);

    
    List<Map<String, Object>> listRecentClassify(long caseId);
}
