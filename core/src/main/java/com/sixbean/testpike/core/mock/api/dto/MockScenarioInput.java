package com.sixbean.testpike.core.mock.api.dto;

/**
 * Scenario save input — the stateful machine declaration of one mock space
 * . The definition travels as raw JSON text and is shape-
 * checked by the shared declaration kernel at the save-chain single point, so
 * a broken machine fails here with plain words instead of confusing traffic
 * later.
 *
 * @param name scenario label stubs bind to (unique within the space)
 * @param definitionJson machine declaration as JSON object text
 * ({@code states}/{@code initial}/{@code scope}/
 * {@code transitions}); null/blank is refused, the column
 * default '{}' is never a legal machine
 */
public record MockScenarioInput(
        String name,
        String definitionJson) {
}
