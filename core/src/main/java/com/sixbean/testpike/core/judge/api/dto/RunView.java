package com.sixbean.testpike.core.judge.api.dto;

import java.util.Map;

/**
 *
 * @param targetType pipeline / external
 */
public record RunView(Long id, String rubricName, String rubricDomain, Integer rubricVersion,
                      Map<String, Object> rubricSnapshot, String evalsetName,
                      Integer evalsetVersion, String evalsetDigest, String targetType,
                      String targetRef, Long llmConfigId, Integer status, Long createdBy,
                      String createdAt, String finishedAt) {
}
