package com.sixbean.testpike.core.deadletter.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterBrowseQuery;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterClassCount;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterLedgerRow;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterQueueCount;

import java.util.List;

/**
 * Read-side atomic queries over the dead-letter ledger .
 * <p>The ops list page consumes exactly these three shapes — per-queue grouping,
 * failure-class distribution, paged sample browsing — through the admin composition
 * layer; the backlog basis is always ledger rows in a status (pending by default),
 * write path; nothing here mutates the table.
 */
public interface DeadLetterLedgerQueryService {

    /**
     * Count ledger rows grouped by DLQ for one status.
     *
     * @param status pending/replayed/voided; null or blank defaults to pending
     * @return groups with strictly positive counts, busiest queue first; queues
     *         without rows in the status are omitted entirely
     */
    List<DeadLetterQueueCount> queueBacklog(String status);

    /**
     * Count ledger rows grouped by failure class within an optional queue scope.
     *
     * @param queueName restrict to one DLQ (nullable = all queues)
     * @param status    pending/replayed/voided; null or blank defaults to pending
     * @return classes actually present in the scope, most frequent first
     */
    List<DeadLetterClassCount> classDistribution(String queueName, String status);

    /**
     * Paged sample browsing ordered newest first, carrying payload and plain reason.
     * <p>Unknown status/class literals and oversize pages fail closed with a
     * human-readable validation error — a typo'd filter must never read as an
     * empty DLQ.
     */
    PageResult<DeadLetterLedgerRow> browse(DeadLetterBrowseQuery query);
}
