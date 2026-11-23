package com.sixbean.testpike.core.apiaccess.api.dto;

/**
 * One catalog entry projected as an MCP tool for callers outside the product.
 *
 * <p>The projection is produced where the catalog lives, never re-derived by the caller: an external
 * surface that kept its own copy of "what this endpoint takes" would be a second source of the same
 * contract, and the day an endpoint changes it would be wrong in exactly one place.
 *
 * @param name            stable tool name derived from method + path (never a sequence number)
 * @param description     what an agent may do with it, in operating terms (defaults, limits, names)
 * @param inputSchemaJson JSON Schema object for the arguments the entry declares
 * @param endpointKey     catalog key to call with those arguments
 */
public record ApiAccessMcpToolView(
        String name,
        String description,
        String inputSchemaJson,
        String endpointKey) {
}
