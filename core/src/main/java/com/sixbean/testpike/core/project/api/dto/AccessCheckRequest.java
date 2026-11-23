package com.sixbean.testpike.core.project.api.dto;


public record AccessCheckRequest(Long projectId, Long userId, String requiredRole) {
}
