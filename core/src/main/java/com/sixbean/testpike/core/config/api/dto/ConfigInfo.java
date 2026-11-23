package com.sixbean.testpike.core.config.api.dto;

/**
 * System configuration information (K8 output parameter; when encryptFlag=1 for sensitive values, value is a masked string ******).
 *
 * @param id          ID
 * @param configKey   key
 * @param configValue plaintext (non-sensitive) or a masked string (sensitive).
 * @param groupName group.
 * @param valueType value type.
 * @param encryptFlag 1 = encrypted storage of sensitive values.
 * @param description description.
 */
public record ConfigInfo(Long id, String configKey, String configValue, String groupName,
                         String valueType, int encryptFlag, String description) {
}
