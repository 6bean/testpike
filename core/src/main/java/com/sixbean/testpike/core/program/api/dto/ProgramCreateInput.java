package com.sixbean.testpike.core.program.api.dto;

/**
 * Create-program payload for the internal endpoint.
 *
 * @param name        program display name (unique among active programs)
 * @param description optional free text describing the grouping intent
 */
public record ProgramCreateInput(String name, String description) {
}
