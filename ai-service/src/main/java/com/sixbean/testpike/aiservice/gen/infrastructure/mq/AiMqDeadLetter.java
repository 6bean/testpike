package com.sixbean.testpike.aiservice.gen.infrastructure.mq;

import com.sixbean.testpike.common.mq.MqDeadLetterTopology;


public final class AiMqDeadLetter {

    /** Dead-letter exchange (direct, durable) — literals single-sourced in common. */
    public static final String DLX_EXCHANGE = MqDeadLetterTopology.DLX_EXCHANGE;

    /** Dead-letter queue name suffix (testpike.ai-gen → testpike.ai-gen.dlq). */
    public static final String DLQ_SUFFIX = MqDeadLetterTopology.DLQ_SUFFIX;

    private AiMqDeadLetter() {
    }

    
    public static String dlqName(String queueName) {
        return MqDeadLetterTopology.dlqName(queueName);
    }
}
