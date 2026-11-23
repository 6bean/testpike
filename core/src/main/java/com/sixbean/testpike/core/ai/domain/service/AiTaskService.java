package com.sixbean.testpike.core.ai.domain.service;

import com.sixbean.testpike.core.ai.api.dto.AiTaskCreateInput;
import com.sixbean.testpike.core.ai.api.dto.AiTaskInfo;


public interface AiTaskService {

    
    long create(AiTaskCreateInput input);

    
    AiTaskInfo get(long taskId);

    /**
 * Latest single-line generation task of one project :
 * task_type=1 rows not owned by any batch, newest first. Returns null when
 * the project never ran a single generation — the caller shows the honest
 * empty panel instead of a stale local copy. Used by the AI generation page
 * to re-attach a running task after the user leaves and comes back.
 */
    AiTaskInfo findLatestSingle(long projectId);

    /**
 * Live generation markers for a set of requirement points ( recovery
 * batch): queued(1)/running(2) case-generation tasks joined by
 * requirement_id, filtered by the project segment — a cross-project id set
 * simply answers nothing. An empty/null id list answers an empty list
 * without a database round trip.
 */
    java.util.List<com.sixbean.testpike.core.ai.api.dto.AiRequirementLiveInfo>
            liveByRequirements(long projectId, java.util.List<Long> requirementIds);

    /**
 * Cursor window of one script-generation run's process events ( S4
 * segment B): rows of the tp_ai_task_event steps-gen face landed after
 * {@code cursor}, ascending by row id, window clamped 10~1000 with the
 * hot key ai.stream.chunk-size as the default — the same cursor contract
 * the extraction tail rides, served by the shared window door. Read-only
 * observation: an unknown task honestly answers an empty window, and an
 * unreachable ledger door never fails the caller.
 */
    java.util.List<com.sixbean.testpike.core.events.api.dto.TaskEventRow>
            processEvents(long taskId, long cursor, Integer limit);

    
    void markRunning(long taskId);

    
    void markDone(long taskId, Long caseId);

    
    void markFailed(long taskId, String errorCode, String errorMsg);

    /**
     * Stop a live generation task (the process tail drawer's stop door):
     * settles a queued or running task as cancelled (5). A task that
     * already reached done/failed honestly rejects the stop (state machine),
     * a repeated stop is idempotent, an unknown id answers not-found. Once
     * cancelled, every late status writeback of the stopped run is refused,
     * so no result produced after the stop can be attached to the task.
     */
    void cancelTask(long taskId);

    
    void markModel(long taskId, String modelName);

    
    void retry(long taskId);

    
    void pinTemplate(long taskId, Long templateVersionId);
}
