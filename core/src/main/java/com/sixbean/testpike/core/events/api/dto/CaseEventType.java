package com.sixbean.testpike.core.events.api.dto;


public enum CaseEventType {

    
    REVIEW_CHANGED("case.reviewed"),

    
    ASSIGNED("case.assigned"),

    
    BATCH_ASSIGNED("case.batch-assigned"),

    
    DELETED("case.deleted"),

    
    INVALIDATED("case.invalidated"),

    
    PROJECT_INVALIDATED("kb.project-invalidated"),

    
    EXEC_TASK_FAILED("exec.task.failed"),

    /**
     * Batch finalized: emitted when a batch really advances to terminal status 6 (done)
     * or 7 (partial failed). Payload tail keys batchId + targetStatus carry the finalize
     * outcome; consumer = ai-service failure-root-cause clustering (proactive insights).
     * Cancelled batches never emit (idempotent guard in BatchFinalizeSupport).
     */
    EXEC_BATCH_FINALIZED("exec.batch.finalized");

    private final String routingKey;

    CaseEventType(String routingKey) {
        this.routingKey = routingKey;
    }

    public String routingKey() {
        return routingKey;
    }
}
