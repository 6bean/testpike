package com.sixbean.testpike.common.mq;

import java.util.List;

/**
 *
 * @param requirementId requirement the event is about (core
 * RequirementEventMessage same key; null on the case
 * side so the consumer dispatches by presence)
 * @param requirementVersion row version after the change — rides the consumer
 * idempotency key kb-ingest:req:{id}:{version}
 */
public record CaseIngestEventMessage(String eventType, Long caseId, Long projectId,
                                     Long operatorId, String action, String remark,
                                     Long assigneeId, List<Long> caseIds, Integer batchSize,
                                     Long occurredAt, Integer caseVersion,
                                     Long requirementId, Integer requirementVersion) {

    /** Case-side convenience shape (all legacy construction sites keep compiling). */
    public CaseIngestEventMessage(String eventType, Long caseId, Long projectId,
                                  Long operatorId, String action, String remark,
                                  Long assigneeId, List<Long> caseIds, Integer batchSize,
                                  Long occurredAt, Integer caseVersion) {
        this(eventType, caseId, projectId, operatorId, action, remark, assigneeId, caseIds,
                batchSize, occurredAt, caseVersion, null, null);
    }
}
