package com.sixbean.testpike.core.config.api.dto;

/**
 * Input parameters for creating or updating system configuration (K8, PRD 4.7).
 *
 * @param configKey The configuration key must be unique, such as recording.retention.days.
 * @param configValue The configuration value is encrypted on the server side when encryptFlag=1, and no plaintext is stored in the database.
 * @param groupName The group name is one of model, AI, system, security, notification, or APP automation.
 * @param valueType The value type is one of string, int, bool, or json.
 * @param encryptFlag The encryptFlag value 1 means sensitive values are encrypted for storage and returned masked.
 * @param description description.
 */
public record ConfigInput(String configKey, String configValue, String groupName,
                          String valueType, int encryptFlag, String description) {
}
