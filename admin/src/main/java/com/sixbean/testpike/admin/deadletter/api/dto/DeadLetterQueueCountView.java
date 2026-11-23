package com.sixbean.testpike.admin.deadletter.api.dto;

/**
 * Mirror of the core per-queue grouping row .
 * <p>Backlog basis = ledger rows in the requested status (pending by default),
 * never the Rabbit queue depth.
 */
public record DeadLetterQueueCountView(String queueName, long pendingCount) {
}
