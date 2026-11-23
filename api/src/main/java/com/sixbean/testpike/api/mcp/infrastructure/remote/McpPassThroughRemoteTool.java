package com.sixbean.testpike.api.mcp.infrastructure.remote;

/**
 * Contract mirror of core's pass-through tool projection (03 GET/POST
 * /internal/api-access/mcp-tools; mirror of core {@code ApiAccessMcpToolView}, JSON field names
 * aligned one by one — the api module must not depend on the core module, same basis as
 * {@link McpAssemblyRemoteServer}).
 *
 * @param name            tool name as projected by core (method + path slug, stable)
 * @param description     operating description shown to the agent (parameters, limits, ownership)
 * @param inputSchemaJson JSON Schema (object root) over the entry's declared parameters
 * @param endpointKey     catalog key to dial for this tool
 */
public record McpPassThroughRemoteTool(
        String name,
        String description,
        String inputSchemaJson,
        String endpointKey) {
}
