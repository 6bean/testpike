package com.sixbean.testpike.core.ai.api.dto;

/**
 * AI generation task creation input parameters (enqueue the generation request; database design table tp_ai_task).
 *
 * @param projectId project ID (used throughout; return 404 if not found).
 * @param operatorId initiator (passed through from X-Operator-Id).
 * @param taskType 1=test case generation, 2=script generation.
 * @param requirementText generation input: natural-language requirement or manual test objective (nullable when taskType=2; script regeneration follows the test case).
 * @param requirementId optional associated requirement point (acceptance coverage input).
 * @param caseId target test case for script generation task (required when taskType=2; see /cases/{id}/script/regenerate).
 * @param batchId optional batch association (batch generation: batch-created child tasks are associated with tp_ai_batch.id; single generation is null).
 */
public record AiTaskCreateInput(long projectId, long operatorId, int taskType,
                                String requirementText, Long requirementId, Long caseId, Long batchId) {

    /** Convenience constructor for test case generation (taskType=1, no target test case, no batch association). */
    public AiTaskCreateInput(long projectId, long operatorId, int taskType,
                             String requirementText, Long requirementId) {
        this(projectId, operatorId, taskType, requirementText, requirementId, null, null);
    }
}
