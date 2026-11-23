package com.sixbean.testpike.aiservice.knowledge.domain.service;

import com.sixbean.testpike.common.mq.AiKbIngestMessage;
import com.sixbean.testpike.common.mq.CaseIngestEventMessage;


public interface KnowledgeIngestService {

    
    void ingest(AiKbIngestMessage message);

    
    void ingestCase(CaseIngestEventMessage message);

    
    void cleanupCase(long caseId);

    
    void cleanupProject(long projectId);

    
    void applyExcellentFlag(CaseIngestEventMessage message);

    /**
     * Requirement ingest: pulls the core requirement brief through the
     * existing CoreGateway.getRequirement single source, assembles title +
     * acceptance criteria into one chunk (masked before write), upserts the
     * REQUIREMENT ledger row and re-vectorizes delete-then-insert by doc_id
     * so the newest approved version always wins.
     */
    void ingestRequirement(CaseIngestEventMessage message);

    /**
     * Requirement retire: ledger row goes INACTIVE, vector rows are
     * physically deleted so AI retrieval can no longer reach stale criteria
     * (self-contamination loop closed). Naturally idempotent — a never
     * ingested or already retired requirement is a no-op.
     */
    void retireRequirement(long requirementId);
}
