package com.sixbean.testpike.common.mq;

/**
 *
 * @param genSeq Generation sequence (restart fix 11-20): 0 for the first
 * run, +1 per retry/restart. The consumer dedup key carries
 * it — a legacy message without the field deserializes to
 * 0 (same behaviour as a first run). Without the suffix a
 * restart re-publish of the SAME taskId replayed the first
 * run's completion stamp, the fresh message was swallowed
 * as DUPLICATE and the queue row never left the queued state.
 */
public record AiGenMessage(long taskId, long projectId, long operatorId, int taskType,
                           String requirementText, Long requirementId, Long caseId, int genSeq) {

    /** Legacy 7-arg shape = first generation (genSeq 0). Keeps every existing
     * publish call site compiling and byte-compatible on the wire. */
    public AiGenMessage(long taskId, long projectId, long operatorId, int taskType,
                        String requirementText, Long requirementId, Long caseId) {
        this(taskId, projectId, operatorId, taskType, requirementText, requirementId, caseId, 0);
    }
}
