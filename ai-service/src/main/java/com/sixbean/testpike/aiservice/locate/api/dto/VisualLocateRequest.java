package com.sixbean.testpike.aiservice.locate.api.dto;

import java.util.List;
import java.util.Map;


public record VisualLocateRequest(
        Long taskId,
        Long projectId,
        String screenshotB64,
        String screenshotMime,
        List<Map<String, Object>> a11yCandidates,
        Map<String, Object> step,
        String action,
        String verb) {
}
