package com.sixbean.testpike.core.suites.api.dto;

import java.util.List;

/**
 *
 * @param mockSpaceId declared mock binding : null = column
 * untouched; 0 = unbind back to the real environment;
 * otherwise a published space of this project — the same
 * MockSpaceBindingGate as the create chain guards it
 */
public record SuiteUpdateInput(String name, List<Long> caseIds, List<Long> envIds,
                               List<Long> defaultAssigneeIds, String cronExpr, Integer status,
                               String automationTarget, String appautoEngine,
                               Long appautoEnvProfileId, Long mockSpaceId) {

    /** Compatibility 9-arg constructor (no mock binding) — existing call sites untouched. */
    public SuiteUpdateInput(String name, List<Long> caseIds, List<Long> envIds,
                            List<Long> defaultAssigneeIds, String cronExpr, Integer status,
                            String automationTarget, String appautoEngine, Long appautoEnvProfileId) {
        this(name, caseIds, envIds, defaultAssigneeIds, cronExpr, status,
                automationTarget, appautoEngine, appautoEnvProfileId, null);
    }

    
    public SuiteUpdateInput(String name, List<Long> caseIds, List<Long> envIds,
                            List<Long> defaultAssigneeIds, String cronExpr, Integer status) {
        this(name, caseIds, envIds, defaultAssigneeIds, cronExpr, status, null, null, null);
    }
}
