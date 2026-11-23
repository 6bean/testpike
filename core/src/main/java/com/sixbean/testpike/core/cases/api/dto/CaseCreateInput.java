package com.sixbean.testpike.core.cases.api.dto;

import java.util.List;

/**
 * Input parameters for case creation (POST /cases, shared by manual and AI persistence to database).
 *
 * @param projectId project ID (propagated throughout; 404 if it does not exist).
 * @param title test target (required, ≤300).
 * @param precondition precondition.
 * @param expectedResult expected result summary.
 * @param priority 0=P0 1=P1 2=P2 (default 1).
 * @param confidence 1=high 2=medium 3=low (default 2, AI self-assessment quality gate).
 * @param endType web/mobile/pos/multi (default web).
 * @param status status (default 1 pending review; 9-state constraint 1~9).
 * @param phase case phase 1=smoke 2=functional 3=regression 4=release verification (null=unassigned).
 * @param source 1=AI generated 2=template 3=legacy import 4=clone 5=explore-to-case
 * 6=sandbox promotion
 * @param sourceRef template source or clone source annotation.
 * @param steps step list (may be empty; server aggregates steps_text for search).
 * @param tagIds multiple selected tag IDs (validated as belonging to the project; null or empty means no tags).
 * @param assigneeId assignee ID (must belong to the project and have the tester role; 0 or null means no assignee).
 * @param automationTarget automation target web/app/mp/harmony; null or empty means unattached and uses web semantics.
 * Orthogonal to the business-side attributes of endType; normalization and violation rejection are performed at the dispatch assembly component.
 * @param appautoEngine APP/MP target engine code (tp_appauto_engine.code; must be empty for web or unattached).
 * @param appautoEnvProfileId environment profile reference (required for mp target and must include projectPath; 0 or null means not attached).
 * @param mockSpaceId declarative mock binding: 0/null = run
 * against the real environment; otherwise a published
 * space of this project — existence/ownership/published
 * checks live in one place, MockSpaceBindingGate
 */
public record CaseCreateInput(long projectId, String title, String precondition, String expectedResult,
                              Integer priority, Integer confidence, String endType,
                              Integer status, Integer phase, Integer source, String sourceRef,
                              List<CaseStepInput> steps, List<Long> tagIds, Long assigneeId,
                              String automationTarget, String appautoEngine,
                              Long appautoEnvProfileId, Long mockSpaceId) {

    /** Compatibility 17-arg constructor (no mock binding) — existing call sites (clone/import/explore/tests) untouched. */
    public CaseCreateInput(long projectId, String title, String precondition, String expectedResult,
                           Integer priority, Integer confidence, String endType,
                           Integer status, Integer phase, Integer source, String sourceRef,
                           List<CaseStepInput> steps, List<Long> tagIds, Long assigneeId,
                           String automationTarget, String appautoEngine, Long appautoEnvProfileId) {
        this(projectId, title, precondition, expectedResult, priority, confidence, endType,
                status, phase, source, sourceRef, steps, tagIds, assigneeId,
                automationTarget, appautoEngine, appautoEnvProfileId, null);
    }

    /** Compatible 12-parameter constructor (without tags, assignee, or automation target) avoids breaking existing call sites (import, clone, test). */
    public CaseCreateInput(long projectId, String title, String precondition, String expectedResult,
                           Integer priority, Integer confidence, String endType,
                           Integer status, Integer phase, Integer source, String sourceRef,
                           List<CaseStepInput> steps) {
        this(projectId, title, precondition, expectedResult, priority, confidence, endType,
                status, phase, source, sourceRef, steps, null, null, null, null, null);
    }

    /** Compatible 14-parameter constructor (without the three automation-target fields) avoids breaking existing call sites. */
    public CaseCreateInput(long projectId, String title, String precondition, String expectedResult,
                           Integer priority, Integer confidence, String endType,
                           Integer status, Integer phase, Integer source, String sourceRef,
                           List<CaseStepInput> steps, List<Long> tagIds, Long assigneeId) {
        this(projectId, title, precondition, expectedResult, priority, confidence, endType,
                status, phase, source, sourceRef, steps, tagIds, assigneeId, null, null, null);
    }
}
