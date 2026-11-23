package com.sixbean.testpike.aiservice.knowledge.infrastructure.mq;

import com.sixbean.testpike.aiservice.knowledge.domain.service.KnowledgeIngestService;
import com.sixbean.testpike.common.idempotent.Idempotent;
import com.sixbean.testpike.common.mq.AiKbIngestMessage;
import org.springframework.stereotype.Component;


@Component
public class KbIngestIdempotentRunner {

    private final KnowledgeIngestService knowledgeIngestService;

    public KbIngestIdempotentRunner(KnowledgeIngestService knowledgeIngestService) {
        this.knowledgeIngestService = knowledgeIngestService;
    }

    
    @Idempotent(key = "'kb-ingest:doc:' + #message.documentId() + ':' + #message.operatorId()"
            + " + ':' + #message.occurredAt()")
    public String run(AiKbIngestMessage message, String token) {
        knowledgeIngestService.ingest(message);
        return token;
    }
}
