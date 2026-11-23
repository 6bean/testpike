package com.sixbean.testpike.aiservice.gen.infrastructure.remote;

/**
 * FC assembly candidate mirror ( U4; GET
 * /internal/tools/project/{pid}/assembly element — contract mirror of core
 * {@code ToolFcAssemblyItem}, JSON field names aligned one-to-one; ai-service
 * must not depend on the core module, hence the local mirror, same as
 * SkillAssemblyRemoteItem).
 *
 * @param functionName registry name from exec_spec-&gt;&gt;'function' (GENKIT/ANALYSIS
 * kinds only; null otherwise) — the analysis dispatch branch
 * routes by this value, never by the user-facing display name
 */
public record ToolFcAssemblyRemoteItem(
        long toolId,
        String name,
        int type,
        Integer execKind,
        String inputSchemaJson,
        int timeoutMs,
        boolean write,
        String functionName) {

    /**
     * Seven-field shape from before the analysis kind (function name absent).
     * Kept so every pre-existing construction site stays valid without churn.
     */
    public ToolFcAssemblyRemoteItem(long toolId, String name, int type, Integer execKind,
                                    String inputSchemaJson, int timeoutMs, boolean write) {
        this(toolId, name, type, execKind, inputSchemaJson, timeoutMs, write, null);
    }
}
