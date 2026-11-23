package com.sixbean.testpike.aiservice.llm.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;

/**
 * Accumulates one tool call whose fragments the vendor splits across streamed
 * chunks (id/name arrive once, arguments come in pieces, keyed by the chunk's
 * {@code index}). Pure transport-side merging — argument payloads stay raw and
 * untrusted until the dispatcher validates them.
 */
public final class MergingToolCallFragment {

    private final StringBuilder id = new StringBuilder();
    private final StringBuilder name = new StringBuilder();
    private final StringBuilder arguments = new StringBuilder();

    /** Absorbs one tool_calls fragment node from a delta. */
    public void absorb(JsonNode fragment) {
        appendIfPresent(id, fragment.path("id"));
        JsonNode function = fragment.path("function");
        appendIfPresent(name, function.path("name"));
        String argumentsPiece = function.path("arguments").asText("");
        if (!argumentsPiece.isEmpty()) {
            arguments.append(argumentsPiece);
        }
    }

    /** A bare {"index":0,"function":{}} keep-alive fragment is not a call. */
    public boolean isPresentable() {
        return !name.isEmpty() || !arguments.isEmpty() || !id.isEmpty();
    }

    public LlmToolCall toCall() {
        return new LlmToolCall(id.toString(), name.toString(), arguments.toString());
    }

    private static void appendIfPresent(StringBuilder target, JsonNode node) {
        String value = node.asText("");
        if (!value.isEmpty()) {
            target.append(value);
        }
    }
}
