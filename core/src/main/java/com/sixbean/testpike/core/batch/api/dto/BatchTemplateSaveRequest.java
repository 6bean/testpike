package com.sixbean.testpike.core.batch.api.dto;

/**
 * Request body for saving a template (POST /batches/{id}/save-as-template with {templateName}).
 *
 * @param templateName Template name.
 */
public record BatchTemplateSaveRequest(String templateName) {
}
