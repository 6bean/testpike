package com.sixbean.testpike.core.mock.api.dto;

import java.time.LocalDateTime;

/**
 * Scenario view — one machine declaration as consumed by UI and snapshot
 * assembler. The definition is handed back verbatim: parsing semantics live in
 * the shared declaration kernel, never in a DTO-side copy.
 *
 * @param id             row id
 * @param spaceId        owning space
 * @param name           scenario label (unique within the space)
 * @param definitionJson machine declaration (JSON object text)
 * @param createdAt      row creation time
 * @param updatedAt      last mutation time
 */
public record MockScenarioInfo(
        Long id,
        Long spaceId,
        String name,
        String definitionJson,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
