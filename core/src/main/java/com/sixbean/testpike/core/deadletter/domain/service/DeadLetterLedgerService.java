package com.sixbean.testpike.core.deadletter.domain.service;

import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterLedgerInput;

import java.util.Optional;

/**
 * <p>The ledger table is the single egress for every DLQ: listeners book here
 * before acking, so the operational backlog is "rows with status=pending", never Rabbit
 * queue depth. Query/replay/void operations arrive with / — this segment is the
 * write path and its idempotency contract only.
 */
public interface DeadLetterLedgerService {

    /**
     * Book one dead-lettered message exactly once.
     * <p>Idempotent on (messageFingerprint, queueName): a redelivered or re-dead-lettered
     * message returns empty instead of producing a second row. The same fingerprint in a
     * different DLQ is a different operational fact and is booked.
     *
     * @return the new ledger row id, or empty when the message was already booked
     * @throws com.sixbean.testpike.common.exception.ServiceException when the fingerprint,
     *         queue name, payload or failure class is missing (fail-closed: an unattributable
     *         row would poison the replay whitelist)
     */
    Optional<Long> record(DeadLetterLedgerInput input);
}
