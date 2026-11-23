package com.sixbean.testpike.core.cases.domain.component;

/**
 * Use-case field-level update payload (value object).
 * <p>Carries the set of columns to be written in this update after normalization in the CaseServiceImpl update chain: null means the column is not updated.
 * (One-to-one correspondence with SQL COALESCE field-level semantics); assigneeId retains the original encoding (null=no update / 0=clear / other=set).
 * Column-level baseline guard applies only to non-null columns.
 * <p>caseTarget is a composite component of the three automation-target columns: null means the three columns remain unchanged; non-null means atomic overwrite of all three columns.
 * The atomic overwrite uses the validated merged effective values, where null members are persisted as NULL, and the three columns are mutually constrained.
 * The constraints are that web must be empty and mp requires a profile, and the three columns must not be split into three independently nullable fields written separately.
 *
 */
public record CaseFieldUpdate(String title, String precondition, String expectedResult,
                              Integer priority, Integer confidence, String endType,
                              Integer phase, Long assigneeId,
                              com.sixbean.testpike.core.executor.domain.component.AppAutoCaseTarget caseTarget,
                              Long mockSpaceId) {

    /** Compatibility constructor (no mock binding) — existing call sites untouched. */
    public CaseFieldUpdate(String title, String precondition, String expectedResult,
                           Integer priority, Integer confidence, String endType,
                           Integer phase, Long assigneeId,
                           com.sixbean.testpike.core.executor.domain.component.AppAutoCaseTarget caseTarget) {
        this(title, precondition, expectedResult, priority, confidence, endType, phase,
                assigneeId, caseTarget, null);
    }
}
