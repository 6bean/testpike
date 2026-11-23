package com.sixbean.testpike.core.mock.infrastructure.mq;

import com.sixbean.testpike.common.idempotent.Idempotent;
import com.sixbean.testpike.common.mq.MockHitBatchMessage;
import com.sixbean.testpike.core.mock.domain.service.MockHitLedgerService;
import org.springframework.stereotype.Component;

/**
 * Idempotent execution piece of the hit ledger consumer (batchId is the dedup
 * token material): a redelivered batch replays the first landing instead of
 * writing the ledger twice — same shape as the ai-service consumers behind
 * MqIdempotentConsumeSupport.
 */
@Component
public class MockHitIdempotentRunner {

    private final MockHitLedgerService ledgerService;

    public MockHitIdempotentRunner(MockHitLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    /** Land the batch and echo this delivery's token (a replay returns the first token). */
    @Idempotent(key = "'mock-hit:' + #message.batchId()")
    public String run(MockHitBatchMessage message, String token) {
        ledgerService.landBatch(message);
        return token;
    }
}
