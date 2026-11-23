package com.sixbean.testpike.core.tool.api.dto;

/**
 *
 * @param functionName registry function name carried in {@code exec_spec->>'function'}
 * (GENKIT/ANALYSIS kinds; null for other kinds — the display
 * name is user-facing wording and never a routing key)
 */
public record ToolFcAssemblyItem(
        long toolId,
        String name,
        int type,
        Integer execKind,
        String inputSchemaJson,
        int timeoutMs,
        boolean write,
        String functionName) {
}
