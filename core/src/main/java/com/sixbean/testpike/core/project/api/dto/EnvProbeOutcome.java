package com.sixbean.testpike.core.project.api.dto;


public record EnvProbeOutcome(boolean healthy, String msg) {

    public static EnvProbeOutcome ok() {
        return new EnvProbeOutcome(true, null);
    }

    public static EnvProbeOutcome fail(String msg) {
        return new EnvProbeOutcome(false, msg);
    }
}
