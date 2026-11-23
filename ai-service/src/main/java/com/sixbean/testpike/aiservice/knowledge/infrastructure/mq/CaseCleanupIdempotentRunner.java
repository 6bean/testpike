package com.sixbean.testpike.aiservice.knowledge.infrastructure.mq;

import com.sixbean.testpike.aiservice.knowledge.domain.service.KnowledgeIngestService;
import com.sixbean.testpike.common.idempotent.Idempotent;
import com.sixbean.testpike.common.mq.CaseIngestEventMessage;
import org.springframework.stereotype.Component;


@Component
public class CaseCleanupIdempotentRunner {

    private final KnowledgeIngestService knowledgeIngestService;

    public CaseCleanupIdempotentRunner(KnowledgeIngestService knowledgeIngestService) {
        this.knowledgeIngestService = knowledgeIngestService;
    }

    
    @Idempotent(key = "'kb-cleanup:case:' + #message.caseId() + ':' + #message.occurredAt()")
    public String runCase(CaseIngestEventMessage message, String token) {
        knowledgeIngestService.cleanupCase(message.caseId());
        return token;
    }

    
    @Idempotent(key = "'kb-cleanup:proj:' + #message.projectId() + ':' + #message.occurredAt()")
    public String runProject(CaseIngestEventMessage message, String token) {
        knowledgeIngestService.cleanupProject(message.projectId());
        return token;
    }
}
