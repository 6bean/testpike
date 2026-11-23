package com.sixbean.testpike.core.executor.api.dto;

import java.util.Map;


public record SessionArtifactsInput(long taskId, String claimToken, Map<String, String> artifacts) {
}
