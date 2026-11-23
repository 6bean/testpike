package com.sixbean.testpike.core.batch.api.dto;

/**
 * Batch start request body: the start endpoint accepts an optional body, and not sending it from the web has no impact.
 * <p>Use a wrapper body instead of a bare EnvOverrideInput: it reserves extension fields, and under required=false a null body and null envOverrides have the same semantics.
 * null envOverrides are normalized to no override.
 *
 * @param envOverrides environment overrides (all null means no override).
 */
public record BatchStartInput(EnvOverrideInput envOverrides) {
}
