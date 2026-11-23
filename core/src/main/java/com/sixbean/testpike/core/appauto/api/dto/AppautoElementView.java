package com.sixbean.testpike.core.appauto.api.dto;

/**
 * Element library profile row as stored on tp_appauto_element.
 * JSONB columns travel as raw JSON text (same posture as sibling appauto
 * views; the admin bridge and pages parse them).
 */
public record AppautoElementView(
        Long id,
        Long projectId,
        String name,
        String locatorType,
        String locatorConfig,
        String variants,
        Double confidenceThreshold,
        String createdAt,
        String updatedAt) {
}
