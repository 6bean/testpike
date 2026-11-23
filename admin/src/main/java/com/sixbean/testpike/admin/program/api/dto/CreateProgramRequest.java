package com.sixbean.testpike.admin.program.api.dto;

/**
 * Create-program request (admin side of the bridge).
 * Serialized as-is toward core {@code ProgramCreateInput} — same JSON field names.
 *
 * @param name        program display name
 * @param description optional grouping intent
 */
public record CreateProgramRequest(String name, String description) {
}
