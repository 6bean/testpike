package com.sixbean.testpike.core.deadletter.api.dto;

/**
 * One row of the per-queue dead-letter grouping .
 * <p>Backlog metric of the ops page: pending rows of tp_mq_dead_letter per DLQ —
 * so DLQs are normally empty and the table is the backlog).
 *
 * @param queueName the DLQ name (e.g. ai-gen.dlq)
 * @param pendingCount number of ledger rows of this queue in the requested status
 */
public record DeadLetterQueueCount(String queueName, long pendingCount) {
}
