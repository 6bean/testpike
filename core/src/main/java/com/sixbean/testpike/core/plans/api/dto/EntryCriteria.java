package com.sixbean.testpike.core.plans.api.dto;


public record EntryCriteria(boolean requireSmokePass) {

    public static EntryCriteria of(Boolean requireSmokePass) {
        return new EntryCriteria(requireSmokePass == null || requireSmokePass);
    }
}
