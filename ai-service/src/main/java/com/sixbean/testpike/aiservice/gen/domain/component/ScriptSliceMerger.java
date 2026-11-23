package com.sixbean.testpike.aiservice.gen.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.util.JsonOutputExtractor;

import java.util.List;

/**
 * Pure operator that stitches the per-group DSL outputs of a sliced generation
 * back into one whole script . Each group reply is stripped
 * of fences via the shared {@link JsonOutputExtractor} (single source, never a
 * second copy), its {@code steps} array is appended in group order, and the
 * result is serialised once so the merged document is always valid JSON.
 *
 * <p>Envelope posture (consumed by the slice-C dispatch): the whole-script
 * envelope — {@code version}/{@code caseId}/{@code env}/{@code setup} and any
 * other top-level key the 04 contract carries — comes from the FIRST
 * converged group and only {@code steps} is concatenated. Every group is
 * asked to emit the same envelope for its own segment, so taking the first is
 * deterministic; the merged document must still pass the core-side action-JSON
 * validator, which rejects a script without version/caseId/env.
 *
 * <p>Groups keep the step numbering they were asked to produce; renumbering is
 * a caller-side concern of the dispatch segment, not a hidden mutation here.
 */
public final class ScriptSliceMerger {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScriptSliceMerger() {
    }

    /**
     * @param groupDsls per-group script JSON, in group order (fenced shapes allowed)
     * @return merged script JSON carrying the concatenated steps array
     * @throws IllegalArgumentException when a group is unparseable or has no
     *         {@code steps} array — the message names the offending group
     *         ordinal so the failure can be reported per group
     */
    public static String merge(List<String> groupDsls) {
        if (groupDsls == null || groupDsls.isEmpty()) {
            throw new IllegalArgumentException("拆片合并收到空组列表");
        }
        ObjectNode envelope = null;
        ArrayNode mergedSteps = MAPPER.createArrayNode();
        for (int i = 0; i < groupDsls.size(); i++) {
            int ordinal = i + 1;
            JsonNode root;
            try {
                root = MAPPER.readTree(JsonOutputExtractor.extractJson(groupDsls.get(i)));
            } catch (Exception e) {
                throw new IllegalArgumentException("第 " + ordinal + " 组脚本输出无法解析为 JSON", e);
            }
            JsonNode stepsNode = root.path("steps");
            if (!stepsNode.isArray()) {
                throw new IllegalArgumentException("第 " + ordinal + " 组脚本输出缺少 steps 数组");
            }
            if (envelope == null && root.isObject()) {
                envelope = ((ObjectNode) root).deepCopy();
            }
            stepsNode.forEach(mergedSteps::add);
        }
        ObjectNode whole = envelope != null ? envelope : MAPPER.createObjectNode();
        whole.set("steps", mergedSteps);
        return whole.toString();
    }
}
