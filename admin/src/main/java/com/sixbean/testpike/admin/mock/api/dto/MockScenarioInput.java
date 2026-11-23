package com.sixbean.testpike.admin.mock.api.dto;

/**
 * Scenario save input (admin bridge mirror of the core MockScenarioInput
 * record). The machine declaration travels as raw JSON text — shape validation
 * (states/initial/scope/transitions) is the shared declaration kernel at the
 * core save-chain single point, never re-decided on this side (red line 6).
 *
 * @param name           scenario label stubs bind to (unique within the space)
 * @param definitionJson machine declaration as JSON object text
 */
public record MockScenarioInput(
        String name,
        String definitionJson) {
}
