package com.sixbean.testpike.core.ai.api.dto;

/**
 * Live generation marker of one requirement point :
 * answers "does this requirement point have a generation task still in the
 * queue or running right now" straight from tp_ai_task — the ledger itself is
 * the single source, no second book is kept anywhere. The batch id rides
 * along so the requirement page can deep-link into the generating queue
 * panel instead of asking the user to hunt for it.
 *
 * @param requirementId the annotated requirement point
 * @param taskId the live generation task (queued or running)
 * @param batchId owning batch id, null for a single-line generation
 * @param status 1=queued 2=running (live bucket only by contract)
 */
public record AiRequirementLiveInfo(long requirementId, long taskId, Long batchId, int status) {
}
