package com.sixbean.testpike.admin.appauto.api.dto;

/**
 * Element library profile row returned by the admin surface (mirror of the
 * core record; JSONB columns travel as raw JSON text and are parsed by the
 * pages, same posture as the sibling appauto views).
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
