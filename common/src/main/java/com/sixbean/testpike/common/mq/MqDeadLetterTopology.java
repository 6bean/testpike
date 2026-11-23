package com.sixbean.testpike.common.mq;

import java.util.List;

/**
 * Dead-letter topology shared by every consumer queue in the system
 * (03 protocol: a failed consumption retries a bounded number of times and
 * then lands in a dead letter queue — never an infinite requeue storm, and
 * never a silent drop; a dead letter stays replayable after recovery).
 * <p>Shape: one direct DLX {@code testpike.dlx}; each business queue names its
 * dead letter queue {@code <queue>.dlq} and binds it with routing key equal to
 * the original queue name, so the x-dead-letter-routing-key never silently
 * mismatches. ai-service (AiMqDeadLetter) and core (mock hit ledger) bind here
 * — one topology, no second copy of the literals.
 */
public final class MqDeadLetterTopology {

    /** Dead-letter exchange (direct, durable). */
    public static final String DLX_EXCHANGE = "testpike.dlx";

    /** Dead-letter queue name suffix (testpike.mock-hit → testpike.mock-hit.dlq). */
    public static final String DLQ_SUFFIX = ".dlq";

    /** Header carrying the captured listener failure text (AJ batch : the
 * recoverer republishes the rejected message with a plain-language cause-chain
 * so the ledger classifier can type the failure instead of UNKNOWN). */
    public static final String FAILURE_TEXT_HEADER = "testpike-failure-text";

    /** Header carrying the exchange of the original publish when the message was
     *  dead-lettered through the capture recoverer (x-death is absent on that path,
     *  so origin fields ride on headers for the replay primitive). */
    public static final String DEAD_ORIGIN_EXCHANGE_HEADER = "testpike-dead-origin-exchange";

    /** Header carrying the routing key of the original publish (see above). */
    public static final String DEAD_ORIGIN_ROUTING_KEY_HEADER = "testpike-dead-origin-routing-key";

    private MqDeadLetterTopology() {
    }

    /**
     * Every business queue that must have a dead-letter egress (live scan 2026-11-05:
     * 2 core mock queues + 14 ai-service queues). The DLQ ledger listeners and the
     * stock-migration gate enumerate from here — one list, no second copy.
     */
    public static final List<String> BUSINESS_QUEUES = List.of(
            "testpike.mock-hit", "testpike.mock-recording-frame",
            "testpike.ai-gen", "testpike.ai-doc-extract", "testpike.ai-spec-extract",
            "testpike.ai-judge", "testpike.ai-explore", "testpike.ai-kb-ingest",
            "testpike.ai-kb-case-ingest", "testpike.ai-kb-case-cleanup",
            "testpike.ai-training-sample", "testpike.ai-failure-classify",
            "testpike.ai-script-repair", "testpike.ai-chat",
            "testpike.ai-batch-insight", "testpike.ai-mock-gen");

    /** The matching dead-letter queue names in the same order. */
    public static List<String> deadLetterQueueNames() {
        return BUSINESS_QUEUES.stream().map(MqDeadLetterTopology::dlqName).toList();
    }

    /** Original queue name → dead-letter queue name. */
    public static String dlqName(String queueName) {
        return queueName + DLQ_SUFFIX;
    }
}
