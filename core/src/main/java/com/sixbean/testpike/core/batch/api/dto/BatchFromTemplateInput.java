package com.sixbean.testpike.core.batch.api.dto;

/**
 * Input parameters for creating a batch by reusing a template via POST /batches/from-template {templateId,name,envId}.
 *
 * @param templateId Template ID.
 * @param name New batch name (placeholder modification).
 * @param envId Target environment (must belong to the template project; cross-project access is rejected).
 */
public record BatchFromTemplateInput(long templateId, String name, long envId) {
}
