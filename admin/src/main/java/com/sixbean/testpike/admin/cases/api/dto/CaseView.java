package com.sixbean.testpike.admin.cases.api.dto;

import com.sixbean.testpike.admin.tags.api.dto.TagView;

import java.time.LocalDateTime;
import java.util.List;

/**
 *
 * @param priority 0=P0 1=P1 2=P2
 * @param endType web/mobile/pos/multi
 * @param mockSpaceId declared mock binding (; null = runs against the real
 * environment) — mirrors core CaseInfo one-for-one
 */
public record CaseView(long id, String caseNo, long projectId, String title, String precondition,
                       String expectedResult, int priority, int confidence, String endType, int status,
                       Integer phase, int version, Long assigneeId, LocalDateTime assignedAt,
                       int source, String sourceRef,
                       Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt,
                       List<CaseStepView> steps, List<TagView> tags,
                       int flaky, String flakyReason, LocalDateTime flakyMarkedAt,
                       Boolean offlineLinked, int excellent,
                       String automationTarget, String appautoEngine, Long appautoEnvProfileId,
                       Long mockSpaceId, Integer evidenceGrade, String sourceFacet) {

    /** Compatibility constructor for admin-side call sites predating the provenance columns. */
    public CaseView(long id, String caseNo, long projectId, String title, String precondition,
                    String expectedResult, int priority, int confidence, String endType, int status,
                    Integer phase, int version, Long assigneeId, LocalDateTime assignedAt,
                    int source, String sourceRef,
                    Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt,
                    List<CaseStepView> steps, List<TagView> tags,
                    int flaky, String flakyReason, LocalDateTime flakyMarkedAt,
                    Boolean offlineLinked, int excellent,
                    String automationTarget, String appautoEngine, Long appautoEnvProfileId,
                    Long mockSpaceId) {
        this(id, caseNo, projectId, title, precondition, expectedResult, priority, confidence,
                endType, status, phase, version, assigneeId, assignedAt, source, sourceRef,
                createdBy, createdAt, updatedAt, steps, tags, flaky, flakyReason, flakyMarkedAt,
                offlineLinked, excellent, automationTarget, appautoEngine, appautoEnvProfileId,
                mockSpaceId, null, null);
    }

    
    public CaseView withTags(List<TagView> tagList) {
        return new CaseView(id, caseNo, projectId, title, precondition, expectedResult,
                priority, confidence, endType, status, phase, version, assigneeId, assignedAt,
                source, sourceRef,
                createdBy, createdAt, updatedAt, steps, tagList, flaky, flakyReason, flakyMarkedAt,
                offlineLinked, excellent, automationTarget, appautoEngine, appautoEnvProfileId,
                mockSpaceId, evidenceGrade, sourceFacet);
    }

 
    public CaseView withOfflineLinked(boolean offline) {
        return new CaseView(id, caseNo, projectId, title, precondition, expectedResult,
                priority, confidence, endType, status, phase, version, assigneeId, assignedAt,
                source, sourceRef,
                createdBy, createdAt, updatedAt, steps, tags, flaky, flakyReason, flakyMarkedAt,
                offline, excellent, automationTarget, appautoEngine, appautoEnvProfileId,
                mockSpaceId, evidenceGrade, sourceFacet);
    }
}
