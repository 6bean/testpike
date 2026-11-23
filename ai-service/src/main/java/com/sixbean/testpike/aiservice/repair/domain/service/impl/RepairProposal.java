package com.sixbean.testpike.aiservice.repair.domain.service.impl;


public record RepairProposal(boolean valid, String actionJson, String schemaVersion,
                             String modelName, String summary, String invalidReason) {

    public static RepairProposal passed(String actionJson, String schemaVersion,
                                        String modelName, String summary) {
        return new RepairProposal(true, actionJson, schemaVersion, modelName, summary, null);
    }

    public static RepairProposal invalid(String reason) {
        return new RepairProposal(false, null, null, null, null, reason);
    }
}
