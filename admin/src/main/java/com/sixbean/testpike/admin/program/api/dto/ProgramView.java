package com.sixbean.testpike.admin.program.api.dto;

/**
 * Program (project set) view for the admin api layer.
 * Field names mirror the core {@code ProgramInfo} contract one-to-one
 * (cross-service deserialization matches by name — see ProgramBridgeTest).
 *
 * @param id          program id
 * @param name        display name (unique among active programs)
 * @param description free text describing the grouping intent
 * @param state       1 = active, 0 = archived
 * @param createdAt   creation timestamp as rendered by core
 */
public record ProgramView(
        Long id,
        String name,
        String description,
        int state,
        String createdAt) {
}
