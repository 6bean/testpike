package com.sixbean.testpike.aiservice.knowledge.infrastructure.mq;

import com.sixbean.testpike.aiservice.knowledge.domain.service.KnowledgeIngestService;
import com.sixbean.testpike.common.idempotent.Idempotent;
import com.sixbean.testpike.common.mq.CaseIngestEventMessage;
import org.springframework.stereotype.Component;


@Component
public class CaseIngestIdempotentRunner {

    private final KnowledgeIngestService knowledgeIngestService;

    public CaseIngestIdempotentRunner(KnowledgeIngestService knowledgeIngestService) {
        this.knowledgeIngestService = knowledgeIngestService;
    }

    
    @Idempotent(key = "'kb-ingest:case:' + #message.caseId() + ':' + #message.caseVersion() + ':' + #message.action()")
    public String run(CaseIngestEventMessage message, String token) {
        knowledgeIngestService.ingestCase(message);
        return token;
    }

    
    @Idempotent(key = "'kb-ingest:case:' + #message.caseId() + ':' + #message.caseVersion() + ':' + #message.action()")
    public String runExcellent(CaseIngestEventMessage message, String token) {
        knowledgeIngestService.applyExcellentFlag(message);
        return token;
    }

    /**
     * Requirement ingest. Key kb-ingest:req:{id}:{version} carries no
     * action suffix: the requirement family has no PASS/EXCELLENT split, so
     * same-version requeue/DLQ replay dedups to one ingest and a new review
     * version rides a fresh key.
     */
    @Idempotent(key = "'kb-ingest:req:' + #message.requirementId() + ':' + #message.requirementVersion()")
    public String runRequirement(CaseIngestEventMessage message, String token) {
        knowledgeIngestService.ingestRequirement(message);
        return token;
    }

    /**
     * Requirement retire direct call: never occupies an idempotency
     * key — the CHANGED + REVIEW_PASSED fallback pair must run both actions
     * even at the same {id}:{version}, and retirement itself is idempotent.
     */
    public void runRetire(long requirementId) {
        knowledgeIngestService.retireRequirement(requirementId);
    }
}
