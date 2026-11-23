package com.sixbean.testpike.core.repair.api.dto;


public record TrialRepairTaskInput(long sourceTaskId, String schemaVersion, String actionJson) {
}
