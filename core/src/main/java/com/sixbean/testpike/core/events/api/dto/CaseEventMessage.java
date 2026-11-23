package com.sixbean.testpike.core.events.api.dto;

import java.util.List;

/**
 *
 * @param batchId Batch id (tail-appended): EXEC_BATCH_FINALIZED payload subject consumed
 * by the clustering job; always null for other events. Tail-append keeps
 * Jackson deserialization of older messages working (missing field = null).
 * @param targetStatus Batch terminal status after finalize (tail-appended): 6 = done,
 * 7 = partial failed, taken from the run that really advanced the row;
 * always null for other events.
 */
public record CaseEventMessage(
        String eventType,
        Long caseId,
        Long projectId,
        Long operatorId,
        String action,
        String remark,
        Long assigneeId,
        List<Long> caseIds,
        Integer batchSize,
        Long occurredAt,
        Integer caseVersion,
        Long execTaskId,
        Integer execErrorType,
        String execErrorDetail,
        String execScreenshotKey,
        Long batchId,
        Integer targetStatus) {

    
    public CaseEventMessage(String eventType, Long caseId, Long projectId, Long operatorId,
                            String action, String remark, Long assigneeId, List<Long> caseIds,
                            Integer batchSize, Long occurredAt, Integer caseVersion) {
        this(eventType, caseId, projectId, operatorId, action, remark, assigneeId, caseIds,
                batchSize, occurredAt, caseVersion, null, null, null, null, null, null);
    }

    /** Fifteen-arg compatibility constructor : batch tail keys default to null,
 * so every pre-existing publish site (including the failure-classify chain) stays untouched. */
    public CaseEventMessage(String eventType, Long caseId, Long projectId, Long operatorId,
                            String action, String remark, Long assigneeId, List<Long> caseIds,
                            Integer batchSize, Long occurredAt, Integer caseVersion,
                            Long execTaskId, Integer execErrorType, String execErrorDetail,
                            String execScreenshotKey) {
        this(eventType, caseId, projectId, operatorId, action, remark, assigneeId, caseIds,
                batchSize, occurredAt, caseVersion, execTaskId, execErrorType, execErrorDetail,
                execScreenshotKey, null, null);
    }
}
