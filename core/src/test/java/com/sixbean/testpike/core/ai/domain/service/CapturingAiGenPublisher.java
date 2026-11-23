package com.sixbean.testpike.core.ai.domain.service;

import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import com.sixbean.testpike.common.mq.AiGenMessage;
import com.sixbean.testpike.common.mq.AiKbIngestMessage;
import com.sixbean.testpike.common.mq.AiSpecExtractMessage;
import com.sixbean.testpike.common.mq.ExploreRunMessage;
import com.sixbean.testpike.common.mq.JudgeSampleMessage;
import com.sixbean.testpike.common.mq.MockGenMessage;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;

import java.util.ArrayList;
import java.util.List;

/**
 * L2 observer stub of {@link AiGenPublisher}: records the feed-class flag of
 * every publish call (single-arg sends count as regular feed) so tests can
 * assert the fan-out routing posture without a broker. Test-only helper —
 * production publish chains are verified at L3/L4 with the real RabbitMQ
 * channel, never with this stub.
 */
final class CapturingAiGenPublisher implements AiGenPublisher {

    private final List<Boolean> feedFlags = new ArrayList<>();

    /** Feed flags in call order: true = window queue, false = regular queue. */
    List<Boolean> feedFlags() {
        return feedFlags;
    }

    @Override
    public void publish(AiGenMessage message) {
        feedFlags.add(false);
    }

    @Override
    public void publish(AiGenMessage message, boolean windowFeed) {
        feedFlags.add(windowFeed);
    }

    @Override
    public void publishExtract(AiDocExtractMessage message) {
    }

    @Override
    public void publishExploreRun(ExploreRunMessage message) {
    }

    @Override
    public void publishKbIngest(AiKbIngestMessage message) {
    }

    @Override
    public void publishJudgeSample(JudgeSampleMessage message) {
    }

    @Override
    public void publishSpecExtract(AiSpecExtractMessage message) {
    }

    @Override
    public void publishMockGen(MockGenMessage message) {
    }
}
