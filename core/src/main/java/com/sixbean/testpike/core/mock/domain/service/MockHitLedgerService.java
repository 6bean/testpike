package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.common.mq.MockHitBatchMessage;

/**
 * Hit ledger service : core is the single
 * writer of tp_mock_hit_log. The mock-server data plane never touches
 * PostgreSQL — masked observation rows arrive as MQ batches and land here.
 */
public interface MockHitLedgerService {

    /**
     * Land one batch of already-masked rows under the space the batch names.
     * <p>An unknown/blank spaceKey rejects the whole batch with a ServiceException
     * (the message retries and then dead-letters — a visible backlog beats a
     * silent black hole); corrupt per-row json facets degrade honestly to empty
     * containers rather than losing the row.
     */
    void landBatch(MockHitBatchMessage batch);
}
