package com.sixbean.testpike.core.program.api.dto;

/**
 * Update-program payload: rename and/or re-describe the lens itself.
 * Membership and grants are separate endpoints — this never touches them.
 */
public record ProgramUpdateInput(String name, String description) {
}
