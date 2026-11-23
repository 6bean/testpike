package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;
import java.util.Map;


public record AppautoVisualLocateRequest(
        Long taskId,
        Long projectId,
        String screenshotB64,
        String screenshotMime,
        List<Map<String, Object>> a11yCandidates,
        Map<String, Object> step,
        String action,
        String verb) {

    
    public AppautoVisualLocateRequest withProjectId(long resolvedProjectId) {
        return new AppautoVisualLocateRequest(taskId, resolvedProjectId, screenshotB64,
                screenshotMime, a11yCandidates, step, action, verb);
    }
}
