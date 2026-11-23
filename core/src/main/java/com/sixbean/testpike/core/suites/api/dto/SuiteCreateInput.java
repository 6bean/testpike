package com.sixbean.testpike.core.suites.api.dto;

import java.util.List;

/**
 *
 * @param mockSpaceId declared mock binding: 0/null = the suite
 * runs against the real environment; otherwise a published
 * space of this project — one gate, MockSpaceBindingGate
 */
public record SuiteCreateInput(long projectId, String name, List<Long> caseIds, List<Long> envIds,
                               List<Long> defaultAssigneeIds, String cronExpr, Integer status,
                               String automationTarget, String appautoEngine,
                               Long appautoEnvProfileId, Long mockSpaceId) {

    /** Compatibility 10-arg constructor (no mock binding) — existing call sites untouched. */
    public SuiteCreateInput(long projectId, String name, List<Long> caseIds, List<Long> envIds,
                            List<Long> defaultAssigneeIds, String cronExpr, Integer status,
                            String automationTarget, String appautoEngine, Long appautoEnvProfileId) {
        this(projectId, name, caseIds, envIds, defaultAssigneeIds, cronExpr, status,
                automationTarget, appautoEngine, appautoEnvProfileId, null);
    }

    
    public SuiteCreateInput(long projectId, String name, List<Long> caseIds, List<Long> envIds,
                            List<Long> defaultAssigneeIds, String cronExpr, Integer status) {
        this(projectId, name, caseIds, envIds, defaultAssigneeIds, cronExpr, status,
                null, null, null);
    }
}
