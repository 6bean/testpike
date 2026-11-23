package com.sixbean.testpike.core.mcp.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


public final class McpToolSnapshot {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpToolSnapshot() {
    }

    
    public static List<String> enabledNames(String toolsJson) {
        JsonNode arr = parse(toolsJson);
        return arr == null ? List.of() : enabledNames(arr);
    }

    /**
 * Read-only subset of the enabled whitelist ( AO-22 single source of
 * truth): a tool counts as read-only only when its snapshot element carries
 * {@code annotations.readOnlyHint=true} (MCP standard tool annotation,
 * written by the handshake client). Absent/unknown annotation = NOT
 * read-only — fail-closed, because scheduled sync turns one tool call into
 * automatic writes into the knowledge ledger and must never point at a
 * potentially mutating tool.
 *
 * @param toolsJson raw snapshot text; NULL/broken/non-array = empty list
 * @return enabled ∩ readOnlyHint tool names, snapshot order preserved
 */
    public static List<String> readOnlyEnabledNames(String toolsJson) {
        JsonNode arr = parse(toolsJson);
        if (arr == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (JsonNode node : arr) {
            if (node.path("enabled").asBoolean(false)
                    && node.path("annotations").path("readOnlyHint").asBoolean(false)) {
                names.add(node.path("name").asText(""));
            }
        }
        return names;
    }

    /** Whether one enabled snapshot element is annotated read-only (guard face). */
    public static boolean isEnabledReadOnly(String toolsJson, String toolName) {
        return toolName != null && readOnlyEnabledNames(toolsJson).contains(toolName);
    }

    
    public static JsonNode parse(String toolsJson) {
        if (toolsJson == null || toolsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(toolsJson);
            return node.isArray() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    
    public static int enableOnly(JsonNode snapshot, Set<String> names) {
        if (snapshot == null || !snapshot.isArray()) {
            return 0;
        }
        int enabled = 0;
        for (JsonNode node : snapshot) {
            String name = node.path("name").asText("");
            if (names.contains(name) && !node.path("enabled").asBoolean(false)) {
                ((ObjectNode) node).put("enabled", true);
                enabled++;
            }
        }
        return enabled;
    }

    /**
 * Re-probe merge : a successful tools/list probe rewrites
 * tools_json wholesale, so the enabled=true selections made on the management
 * surface must be inherited by tool name. Without the merge, every scheduled
 * sync probe silently resets the whitelist to default-reject and downstream
 * read-only guards lose their source of truth.
 *
 * <p>Merge never relaxes default-reject: only names already enabled in the
 * previous snapshot are flipped to true (delegated to {@link #enableOnly}, the
 * single union-flip component — no second implementation on purpose); tools
 * gone from the fresh snapshot disappear with the element; brand-new tools
 * stay false.
 *
 * @param fresh newly built snapshot array, mutated in place
 * @param previousJson raw previous tools_json; NULL/broken/non-array = no inheritance
 * @return the fresh array for chaining convenience
 */
    public static JsonNode inheritEnabled(JsonNode fresh, String previousJson) {
        JsonNode previous = parse(previousJson);
        if (previous != null) {
            enableOnly(fresh, new LinkedHashSet<>(enabledNames(previous)));
        }
        return fresh;
    }

    
    public static List<String> enabledNames(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (JsonNode tool : arr) {
            if (tool.path("enabled").asBoolean(false)) {
                String name = tool.path("name").asText(null);
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return List.copyOf(names);
    }

    
    public static List<String> stringList(String jsonArray) {
        JsonNode arr = parse(jsonArray);
        if (arr == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode node : arr) {
            if (node.isTextual() && !node.asText().isBlank()) {
                values.add(node.asText());
            }
        }
        return List.copyOf(values);
    }
}
