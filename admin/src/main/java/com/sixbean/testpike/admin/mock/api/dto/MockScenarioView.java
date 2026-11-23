package com.sixbean.testpike.admin.mock.api.dto;

import java.time.LocalDateTime;

/**
 * Scenario view (admin bridge; field-for-field mirror of the core
 * MockScenarioInfo record). Rows arrive in name order from core — the stub
 * editor's scenario picklist and state dropdowns read positions off this list,
 * so the bridge must never re-sort. The definition JSON passes through
 * verbatim: parsing semantics live in the shared declaration kernel, never in
 * a DTO-side copy.
 *
 * @param id             row id
 * @param spaceId        owning space
 * @param name           scenario label (unique within the space)
 * @param definitionJson machine declaration (JSON object text)
 * @param createdAt      row creation time
 * @param updatedAt      last mutation time
 */
public record MockScenarioView(
        Long id,
        Long spaceId,
        String name,
        String definitionJson,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
