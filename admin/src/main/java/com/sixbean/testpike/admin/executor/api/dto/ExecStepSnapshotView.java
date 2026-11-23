package com.sixbean.testpike.admin.executor.api.dto;


public record ExecStepSnapshotView(long taskId, int stepNo, String kind, boolean truncated,
                                   long rawBytes, String request, String response, String assertions,
                                   String tlsMode) {
}
