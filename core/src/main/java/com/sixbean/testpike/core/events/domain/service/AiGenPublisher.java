package com.sixbean.testpike.core.events.domain.service;

import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import com.sixbean.testpike.common.mq.AiGenMessage;
import com.sixbean.testpike.common.mq.AiKbIngestMessage;
import com.sixbean.testpike.common.mq.AiSpecExtractMessage;
import com.sixbean.testpike.common.mq.ExploreRunMessage;
import com.sixbean.testpike.common.mq.JudgeSampleMessage;
import com.sixbean.testpike.common.mq.MockGenMessage;


public interface AiGenPublisher {

    
    String ROUTING_CASE_GENERATE = "ai.case.generate";

    
    String ROUTING_SCRIPT_GENERATE = "ai.script.generate";

    String ROUTING_CASE_GENERATE_WINDOW = "ai.case.generate.window";

    String ROUTING_SCRIPT_GENERATE_WINDOW = "ai.script.generate.window";

 
    String ROUTING_DOC_EXTRACT = "ai.doc.extract";

 
    String ROUTING_EXPLORE_RUN = "ai.explore.run";

 
    String ROUTING_KB_INGEST = "ai.kb.ingest";

 
    String ROUTING_SPEC_EXTRACT = "ai.spec.extract";

 
    String ROUTING_JUDGE_SAMPLE = "ai.judge.sample";

    /** AI mock-drafting route key . */
    String ROUTING_MOCK_GEN = MockGenMessage.ROUTING_KEY;

    
    void publish(AiGenMessage message);

    /**
     * Publish with feed class: windowFeed routes to the priority queue so
     * verification-window tasks never wait behind the regular backlog.
     * Default keeps the regular routing for implementations that do not
     * provide the priority channel.
     */
    default void publish(AiGenMessage message, boolean windowFeed) {
        publish(message);
    }

 
    void publishExtract(AiDocExtractMessage message);

 
    void publishExploreRun(ExploreRunMessage message);

    
    void publishKbIngest(AiKbIngestMessage message);

 
    void publishJudgeSample(JudgeSampleMessage message);

    
    void publishSpecExtract(com.sixbean.testpike.common.mq.AiSpecExtractMessage message);

    /**
 * Publish one AI mock-drafting job .
 * Identity-only payload — the consumer pulls the prompt and the pinned
 * budget snapshot back over the internal endpoint. Fired from the core
 * controller publish point in a non-transactional context, same posture
 * as the spec-extract chain.
 */
    void publishMockGen(MockGenMessage message);
}
