package com.sixbean.testpike.core.cases.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Case information (details include steps; list excludes steps to control payload, and keyword search uses the aggregated steps_text column).
 *
 * @param id case ID
 * @param caseNo case number TC-{project code}-{sequence} (unique across projects)
 * @param projectId project ID (used throughout)
 * @param title test target
 * @param precondition preconditions
 * @param expectedResult expected result summary
 * @param priority       0=P0 1=P1 2=P2
 * @param confidence 1=high 2=medium 3=low
 * @param endType        web/mobile/pos/multi
 * @param status status with 9 states
 * @param phase case phase 1=smoke 2=functional 3=regression 4=release verification (null=unassigned, optional)
 * @param version optimistic locking version number
 * @param assigneeId assignee (set to null when returned to the pool)
 * @param assignedAt last assignment time (starting point for follow-up)
 * @param source 1=AI-generated 2=template 3=existing import 4=clone
 * @param sourceRef template source or clone source annotation
 * @param createdBy creator
 * @param createdAt creation time
 * @param updatedAt update time
 * @param steps steps (populated in details, null in list)
 * @param flaky flaky isolation marker (0=normal, 1=isolated, skipped by batch preflight check, migration item)
 * @param flakyReason most recent flaky determination reason (shown on hover; null=unmarked)
 * @param flakyMarkedAt flaky marking time (written by detection task or one-click isolation)
 * @param excellent excellent case marker (0=normal, 1=excellent, only passed cases can be marked, migration item)
 * @param automationTarget automation target web/app/mp/harmony (null=not attached, treated as web semantics)
 * @param appautoEngine APP/MP target engine code (has value only when target is attached)
 * @param appautoEnvProfileId environment profile reference (has value only when target is attached)
 * @param mockSpaceId     declared mock binding (null = runs against
 *                        the real environment; a published space id when bound)
 * @param evidenceGrade   review-queue evidence level: 0 none, 1 off-agent self report,
 *                        2 off-agent evidence pack, 3 platform re-proof (V552 column)
 * @param sourceFacet     provenance facet derived once server-side by CaseSourceFacet
 *                        (chat / off_agent / import / manual); pages never re-derive it
 */
public record CaseInfo(long id, String caseNo, long projectId, String title, String precondition,
                       String expectedResult, int priority, int confidence, String endType, int status,
                       Integer phase, int version, Long assigneeId, LocalDateTime assignedAt,
                       int source, String sourceRef,
                       Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt,
                       List<CaseStepInfo> steps,
                       int flaky, String flakyReason, LocalDateTime flakyMarkedAt,
                       int excellent,
                       String automationTarget, String appautoEngine, Long appautoEnvProfileId,
                       Long mockSpaceId, Integer evidenceGrade, String sourceFacet) {

    /** Compatibility constructor for call sites written before the provenance columns existed. */
    public CaseInfo(long id, String caseNo, long projectId, String title, String precondition,
                    String expectedResult, int priority, int confidence, String endType, int status,
                    Integer phase, int version, Long assigneeId, LocalDateTime assignedAt,
                    int source, String sourceRef,
                    Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt,
                    List<CaseStepInfo> steps,
                    int flaky, String flakyReason, LocalDateTime flakyMarkedAt,
                    int excellent,
                    String automationTarget, String appautoEngine, Long appautoEnvProfileId,
                    Long mockSpaceId) {
        this(id, caseNo, projectId, title, precondition, expectedResult, priority, confidence,
                endType, status, phase, version, assigneeId, assignedAt, source, sourceRef,
                createdBy, createdAt, updatedAt, steps, flaky, flakyReason, flakyMarkedAt,
                excellent, automationTarget, appautoEngine, appautoEnvProfileId, mockSpaceId,
                null, null);
    }

    /** Detail view: fill in the step list (record-derived copy keeps immutability). */
    public CaseInfo withSteps(List<CaseStepInfo> stepList) {
        return new CaseInfo(id, caseNo, projectId, title, precondition, expectedResult,
                priority, confidence, endType, status, phase, version, assigneeId, assignedAt,
                source, sourceRef,
                createdBy, createdAt, updatedAt, stepList, flaky, flakyReason, flakyMarkedAt, excellent,
                automationTarget, appautoEngine, appautoEnvProfileId, mockSpaceId,
                evidenceGrade, sourceFacet);
    }
}
