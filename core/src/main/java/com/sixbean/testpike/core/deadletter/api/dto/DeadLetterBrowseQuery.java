package com.sixbean.testpike.core.deadletter.api.dto;

/**
 * Filter+paging input for paged dead-letter browsing .
 * <p>Page size is validated by the shared PageQueryPolicy (reject &gt;200), status
 * defaults to "pending" when null/blank so the default view is exactly the backlog;
 * unknown filter literals fail closed instead of silently matching nothing.
 *
 * @param queueName restrict to one DLQ (nullable = all queues)
 * @param failureClass restrict to one failure class (nullable = all classes)
 * @param status pending/replayed/voided (nullable/blank defaults to pending)
 * @param page 1-based page number
 * @param size page size, 1..200
 */
public record DeadLetterBrowseQuery(
        String queueName,
        String failureClass,
        String status,
        int page,
        int size) {
}
