package com.sixbean.testpike.core.monitor.domain.component;


public record MonitorProbeResult(boolean success, Integer latencyMs, String cause) {

    public static MonitorProbeResult ok(Integer latencyMs) {
        return new MonitorProbeResult(true, latencyMs, null);
    }

    public static MonitorProbeResult fail(Integer latencyMs, String cause) {
        return new MonitorProbeResult(false, latencyMs, cause);
    }
}
