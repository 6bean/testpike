package com.sixbean.testpike.admin.executor.infrastructure.remote.client;

import com.sixbean.testpike.admin.executor.api.dto.BatchCaseRerunView;
import com.sixbean.testpike.admin.executor.api.dto.CaseExecHistoryView;
import com.sixbean.testpike.admin.executor.api.dto.ExecStepEvidenceView;
import com.sixbean.testpike.admin.executor.api.dto.ExecStepSnapshotView;
import com.sixbean.testpike.admin.executor.api.dto.ExecTaskView;
import com.sixbean.testpike.admin.executor.api.dto.ExecutorSelfCheckView;
import com.sixbean.testpike.admin.executor.api.dto.ExecutorView;
import com.sixbean.testpike.admin.executor.api.dto.ParallelWaterLevelView;
import com.sixbean.testpike.admin.executor.api.dto.PoolView;
import com.sixbean.testpike.admin.executor.api.dto.ScreenshotView;

import java.util.List;
import java.util.Map;


public interface CoreExecClient {

 
    long createTask(long projectId, long caseId, String schemaVersion, String actionJson,
                    Long envId, long operatorId);

 
    BatchCaseRerunView rerunBatchCase(long taskId, long operatorId, String engineCode);

    
    List<ExecutorView> listExecutors();

 
    ExecutorSelfCheckView selfCheck(long executorId);

    
    default Map<String, Object> listTasks(long projectId, int page, int size) {
        return listTasks(projectId, page, size, null, null, null, null);
    }

    /**
     * Paged task read narrowed by an optional whitelisted sort pair, an
     * optional batch id and a comma separated status whitelist (AW2
     * batch-promotion picker: the picker asks core for one batch's
     * failed/timeout rows only). The narrowing is forwarded verbatim —
     * the bridge decides nothing itself; an off-list status value is
     * refused fail-closed single-source in core.
     */
    Map<String, Object> listTasks(long projectId, int page, int size, String sortBy, String sortDir,
                                  Long batchId, String statusIn);

    
    ExecTaskView getTask(long taskId);

    
    List<ScreenshotView> listScreenshotUrls(long taskId);

 
    List<ExecStepSnapshotView> listStepSnapshots(long taskId);

 
    List<ExecStepEvidenceView> listStepEvidence(long taskId);

 
    Map<String, Object> readMonitorFrames(long taskId);

 
    Map<String, Object> listCaseHistory(long caseId, long projectId, int page, int size);

 /** Executor-dimension task page (K2 executor log: group counts + filtered
 * list; list already mapped to ExecTaskView). Optional sort pair :
 * null/blank = historical id DESC. */
    Map<String, Object> listExecutorTasks(long executorId, String statusGroup, int page, int size,
                                          String sortBy, String sortDir);

 
    String getTaskLog(long taskId);

    
    Map<String, Object> cancelTask(long taskId, long operatorId);

 
    Map<String, Object> batchCancel(List<Long> taskIds, long operatorId);

 /** Running-process page (cross-batch status=2 window {list, total}).
 * optional sort pair, null/blank = historical id ASC. */
    Map<String, Object> listRunning(int page, int size, String sortBy, String sortDir);

 
    ParallelWaterLevelView parallelWaterLevel();

 
    List<PoolView> listPools();
}
