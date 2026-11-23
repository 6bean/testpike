package com.sixbean.testpike.admin.ai.infrastructure.remote.client;

import com.sixbean.testpike.admin.ai.api.dto.AiRequirementLiveView;
import com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView;
import com.sixbean.testpike.admin.ai.api.dto.AiTaskView;

import java.util.List;
import java.util.Map;


public interface CoreAiTaskClient {

    
    Map<String, Long> createTask(long projectId, long operatorId, int taskType,
                                 String requirementText, Long requirementId);

    
    AiTaskView getTask(long taskId);

    /**
 * Latest single-line generation task of the project ( recovery batch;
 * null when the project never ran one). Read-only — the recovery door of
 * the AI generation page after a leave/return.
 */
    AiTaskView latestSingleTask(long projectId);

    /**
 * Live generation markers for a set of requirement points ( recovery
 * batch): rows whose case generation is still queued/running inside this
 * project segment. An empty id list never dials core.
 */
    List<AiRequirementLiveView> liveByRequirements(long projectId, List<Long> requirementIds);

 
    void retryTask(long taskId);

    /**
     * Stop a queued/running task through the core stop door: core settles the
     * row as cancelled and refuses every later writeback of that run. A
     * terminal task is refused there (state-machine rejection) and an unknown
     * id answers NOT_FOUND — the stop semantic lives single-source in core,
     * this client only forwards.
     */
    void cancelTask(long taskId);

    /**
     * Cursor window of one run's process events (core ledger door; cursor =
     * last row id the viewer saw, an absent limit defers to the hot key
     * ai.stream.chunk-size). Read-only tail used by the steps-generation
     * pump drawer.
     */
    List<AiTaskEventView> taskProcessEvents(long taskId, long cursor, Integer limit);
}
