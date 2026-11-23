package com.sixbean.testpike.admin.program.api.dto;

/**
 * Update-program request: rename and/or re-describe the lens.
 * Mirrors core {@code ProgramUpdateInput}; membership/grants have own endpoints.
 */
public record UpdateProgramRequest(String name, String description) {
}
