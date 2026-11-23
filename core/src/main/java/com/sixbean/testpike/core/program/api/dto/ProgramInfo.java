package com.sixbean.testpike.core.program.api.dto;

/**
 * Program (project set) api view. The lens carries no business data; this record only
 * describes the grouping itself.
 */
public record ProgramInfo(
        Long id,
        String name,
        String description,
        int state,
        String createdAt) {
}
