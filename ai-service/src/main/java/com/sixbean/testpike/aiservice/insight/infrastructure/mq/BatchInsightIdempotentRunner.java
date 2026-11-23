package com.sixbean.testpike.aiservice.insight.infrastructure.mq;

import com.sixbean.testpike.aiservice.insight.domain.service.BatchInsightService;
import com.sixbean.testpike.common.idempotent.Idempotent;
import com.sixbean.testpike.common.mq.BatchFinalizedEventMessage;
import org.springframework.stereotype.Component;

/**
 * Idempotent execution wrapper for the batch-insight consumer (mirrors
 * ClassifyIdempotentRunner). Key = insight:batch:{batchId} — a redelivery of the
 * same finalize event (container requeue / DLQ replay) is deduplicated; distinct
 * batches each run. Re-clustering the same batch overwrites the latest snapshot,
 * the write path in core is latest-wins by design.
 */
@Component
public class BatchInsightIdempotentRunner {

    private final BatchInsightService batchInsightService;

    public BatchInsightIdempotentRunner(BatchInsightService batchInsightService) {
        this.batchInsightService = batchInsightService;
    }

    /** Run clustering and echo this delivery's token (a replay returns the first token → DUPLICATE). */
    @Idempotent(key = "'insight:batch:' + #message.batchId()")
    public String run(BatchFinalizedEventMessage message, String token) {
        batchInsightService.cluster(message);
        return token;
    }
}
