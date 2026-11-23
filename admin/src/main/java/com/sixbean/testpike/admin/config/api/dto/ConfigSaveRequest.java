package com.sixbean.testpike.admin.config.api.dto;

/**
 *
 * @param valueType string/int/bool/json
 */
public record ConfigSaveRequest(String configKey, String configValue, String groupName,
                                String valueType, int encryptFlag, String description) {
}
