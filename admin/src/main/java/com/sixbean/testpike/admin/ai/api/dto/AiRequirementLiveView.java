package com.sixbean.testpike.admin.ai.api.dto;

/**
 * Live generation marker of one requirement point ( recovery batch;
 * mirror of the core AiRequirementLiveInfo row). The requirement page renders
 * it as a "case generating" badge whose click lands on the generating queue.
 *
 * @param requirementId the annotated requirement point
 * @param taskId the live generation task (queued or running)
 * @param batchId owning batch id, null for a single-line generation
 * @param status 1=queued 2=running
 */
public record AiRequirementLiveView(long requirementId, long taskId, Long batchId, int status) {
}
