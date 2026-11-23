package com.sixbean.testpike.core.cases.api.dto;

import java.util.List;

/**
 * Case field-level update request parameters for PUT /cases/{id}, with field-level updates and version optimistic locking.
 * <p>Semantics: null fields are not updated (field-level); when steps is non-null, it is fully replaced (passing an empty list clears the steps).
 * For tagIds, null does not update and non-null overwrites (an empty list clears tags); for assigneeId, null does not update and 0 clears the assignee.
 * version is passed as a separate parameter (CaseUpdateRequest.version) and is not included in this record.
 *
 * @param title: test target; null does not update.
 * @param precondition: precondition; null does not update.
 * @param expectedResult: expected result summary; null does not update.
 * @param priority: priority; null does not update.
 * @param confidence: confidence; null does not update.
 * @param endType: end type; null does not update.
 * @param phase: case phase, 1=smoke, 2=functional, 3=regression, 4=release validation; null does not update; orthogonal to the state machine.
 * @param steps: steps; null does not update, and non-null fully replaces.
 * @param tagIds: tags; null does not update, non-null overwrites, and an empty list clears.
 * @param assigneeId: assignee; null does not update, 0 clears, and otherwise it must belong to the project and pass test-role validation.
 * @param automationTarget: automation target; null does not update, and an empty string clears the assignment back to the unassigned web semantics.
 *                         Otherwise the value must be web/app/mp/harmony, and invalid values are merged into the latest row view and then rejected by the dispatch assembly component with a plain-language message.
 * @param appautoEngine: APP/MP engine code; null does not update, an empty string clears it, and otherwise it is normalized to uppercase and reconciled against the enabled and platform surfaces.
 * @param appautoEnvProfileId: environment profile; null does not update, 0 clears it, and otherwise it must belong to the current project, with MP requiring projectPath.
 * @param mockSpaceId    declarative mock binding: null = column
 *                       untouched; 0 = unbind back to the real environment;
 *                       otherwise a published space of this project — the same
 *                       MockSpaceBindingGate as the create chain guards it
 */
public record CaseUpdateInput(String title, String precondition, String expectedResult,
                              Integer priority, Integer confidence, String endType,
                              Integer phase, List<CaseStepInput> steps, List<Long> tagIds, Long assigneeId,
                              String automationTarget, String appautoEngine,
                              Long appautoEnvProfileId, Long mockSpaceId) {

    /** Compatibility 13-arg constructor (no mock binding) — existing call sites untouched. */
    public CaseUpdateInput(String title, String precondition, String expectedResult,
                           Integer priority, Integer confidence, String endType,
                           Integer phase, List<CaseStepInput> steps, List<Long> tagIds, Long assigneeId,
                           String automationTarget, String appautoEngine, Long appautoEnvProfileId) {
        this(title, precondition, expectedResult, priority, confidence, endType, phase, steps,
                tagIds, assigneeId, automationTarget, appautoEngine, appautoEnvProfileId, null);
    }

    /** The compatible 8-argument constructor (without tags, assignee, and automation target) does not break existing call sites. */
    public CaseUpdateInput(String title, String precondition, String expectedResult,
                           Integer priority, Integer confidence, String endType,
                           Integer phase, List<CaseStepInput> steps) {
        this(title, precondition, expectedResult, priority, confidence, endType, phase, steps,
                null, null, null, null, null);
    }

    /** The compatible 10-argument constructor (without the three automation-target fields) does not break existing call sites. */
    public CaseUpdateInput(String title, String precondition, String expectedResult,
                           Integer priority, Integer confidence, String endType,
                           Integer phase, List<CaseStepInput> steps, List<Long> tagIds, Long assigneeId) {
        this(title, precondition, expectedResult, priority, confidence, endType, phase, steps,
                tagIds, assigneeId, null, null, null);
    }
}
