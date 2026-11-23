package com.sixbean.testpike.core.plugin.api.dto;

/**
 *
 * @param projectId tp_project.id
 */
public record AppliedProjectView(Long projectId, String projectName, String appliedAt) {
}
