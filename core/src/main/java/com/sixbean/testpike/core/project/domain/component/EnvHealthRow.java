package com.sixbean.testpike.core.project.domain.component;


public record EnvHealthRow(long envId, long projectId, String envName, String baseUrl,
                           String configJson, int healthStatus, int failStreak) {
}
