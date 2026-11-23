package com.sixbean.testpike.admin.aichat.infrastructure.remote.client;

/**
 * Contract mirror of the core FC assembly row (U4;
 * GET /internal/tools/project/{pid}/assembly) as the @-mention palette needs
 * it — field names align with core {@code ToolFcAssemblyItem}. The palette
 * shows the document/executable family name only; the gate verdict stays
 * core-side (this row is already the five-way-intersection output).
 *
 * @param toolId tp_tool.id
 * @param name   tool name (the palette label source)
 * @param type   1 = document family, 2 = executable family
 */
public record MentionToolRow(
        long toolId,
        String name,
        int type,
        Integer execKind,
        String inputSchemaJson,
        int timeoutMs,
        boolean write) {
}
