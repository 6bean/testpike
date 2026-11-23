package com.sixbean.testpike.aiservice.knowledge.infrastructure.mq;

import com.sixbean.testpike.aiservice.knowledge.domain.service.KnowledgeIngestService;
import com.sixbean.testpike.common.mq.AiKbIngestMessage;
import com.sixbean.testpike.common.mq.CaseIngestEventMessage;

import java.util.concurrent.atomic.AtomicInteger;


class CaseCleanupCountingService implements KnowledgeIngestService {

    final AtomicInteger ingestCalls = new AtomicInteger();
    final AtomicInteger cleanupCaseCalls = new AtomicInteger();
    final AtomicInteger cleanupProjectCalls = new AtomicInteger();
    final java.util.List<Long> cleanedCaseIds = new java.util.concurrent.CopyOnWriteArrayList<>();
    final java.util.List<Long> cleanedProjectIds = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public void ingest(AiKbIngestMessage message) {
    }

    @Override
    public void ingestCase(CaseIngestEventMessage message) {
        ingestCalls.incrementAndGet();
    }

    @Override
    public void applyExcellentFlag(CaseIngestEventMessage message) {
    }

    @Override
    public void ingestRequirement(CaseIngestEventMessage message) {
    }

    @Override
    public void retireRequirement(long requirementId) {
    }

    @Override
    public void cleanupCase(long caseId) {
        cleanupCaseCalls.incrementAndGet();
        cleanedCaseIds.add(caseId);
    }

    @Override
    public void cleanupProject(long projectId) {
        cleanupProjectCalls.incrementAndGet();
        cleanedProjectIds.add(projectId);
    }
}
