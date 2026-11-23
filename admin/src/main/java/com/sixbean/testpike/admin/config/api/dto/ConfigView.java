package com.sixbean.testpike.admin.config.api.dto;


public record ConfigView(Long id, String configKey, String configValue, String groupName,
                         String valueType, int encryptFlag, String description) {
}
