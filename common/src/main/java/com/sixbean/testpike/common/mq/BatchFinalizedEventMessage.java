package com.sixbean.testpike.common.mq;

import java.util.List;

/**
 * Batch-finalized event MQ message mirror (AO-7 proactive insights, stage 2b).
 * <p>Core publishes EXEC_BATCH_FINALIZED on the shared broadcast exchange
 * {@code testpike.events} with routing key {@code exec.batch.finalized} when a batch
 * really reaches a terminal status; the ai-service clustering consumer reads the
 * failure rows of that batch and lands the result through the core write-back
 * endpoint. ai-service depends on common only and must not import the core api
 * layer, so this record mirrors the core-side {@code CaseEventMessage} JSON keys
 * one by one (ExecFailedEventMessage / CaseIngestEventMessage mirror precedents —
 * Jackson2JsonMessageConverter infers the listener parameter type).
 *
 * @param eventType event type (EXEC_BATCH_FINALIZED; consumer gate checks it together with batchId)
 * @param caseId single-object target case id (always null on this chain)
 * @param projectId owning project, read back from the batch row by core at publish time
 * (H1 isolation anchor; null payloads are skipped by the consumer gate)
 * @param operatorId acting user (finalize path carries none — always null)
 * @param action review action (not consumed on this chain)
 * @param remark remark (not consumed on this chain)
 * @param assigneeId assignment target (not consumed on this chain)
 * @param caseIds batch detail ids (not consumed on this chain)
 * @param batchSize batch count summary (not consumed on this chain)
 * @param occurredAt event time (epoch millis)
 * @param caseVersion case version after review (not consumed on this chain)
 * @param execTaskId failed exec task id (not consumed on this chain)
 * @param execErrorType executor error category (not consumed on this chain)
 * @param execErrorDetail failure detail text (not consumed on this chain)
 * @param execScreenshotKey last-step screenshot object key (not consumed on this chain)
 * @param batchId finalized batch id — clustering subject and idempotency key segment
 * insight:batch:{batchId} (null = not a batch-finalized payload)
 * @param targetStatus terminal status the batch was advanced to: 6 = done, 7 = partial
 * failed (a done batch has nothing to cluster; the service skips it)
 */
public record BatchFinalizedEventMessage(String eventType, Long caseId, Long projectId,
                                         Long operatorId, String action, String remark,
                                         Long assigneeId, List<Long> caseIds, Integer batchSize,
                                         Long occurredAt, Integer caseVersion,
                                         Long execTaskId, Integer execErrorType,
                                         String execErrorDetail, String execScreenshotKey,
                                         Long batchId, Integer targetStatus) {
}
