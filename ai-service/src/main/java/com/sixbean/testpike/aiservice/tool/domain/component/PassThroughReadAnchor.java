package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Server-side project anchoring for the API pass-through read tool (STAMP posture).
 *
 * <p>The pass-through door refuses an anchored entry unless its arguments name a
 * project id, and the dialog never sees the raw numeric id of its own session
 * segment. Without this stamp every cataloged read would dead-end on "name the
 * project id" — the read door would be reachable in the registry but unusable
 * from a conversation. The write side already solves the same problem by
 * re-stamping the owner from the chat session (the core landing door rewrites
 * the project owner from the session and ignores any payload-specified value).
 * This class is the read-side
 * mirror of that posture: when the running conversation carries a server-side
 * validated project segment, the segment — never the model payload — decides which
 * project a pass-through read is anchored to, and a model-nested projectId is
 * overwritten instead of trusted.
 *
 * <p>Failure is fail-soft and additive only: a blank payload, a tool other than
 * the registered pass-through read row, or a missing/zero segment leaves the
 * argument bag byte-identical, so unanchored dialog surfaces keep their old
 * semantics (the door still judges any model-named id by operator membership).
 *
 * <p>The stamp stays shape-blind on purpose: this service has no view of the target
 * entry's declared parameters, and duplicating that knowledge here would be a second
 * source of truth. The core door is the one place that knows the shape, and it conforms
 * the stamped key to what the entry declares (transfer onto {@code projectIds}/{@code pid},
 * drop on entries without a project scope — see {@code ApiAccessIdentityAnchor.conformProjectSlice}
 * on the core side, which mirrors the {@code projectId} literal kept here).
 */
public final class PassThroughReadAnchor {

    /**
     * FC name of the seeded pass-through read capability row (tp_tool name, mirrored by
     * exec_spec-&gt;&gt;'intent' as the idempotent seed anchor). Same literal-posture as the
     * built-in predicates {@code FcToolCatalog.isSearchCall} / {@code ClarifyTool.isClarifyCall}.
     */
    public static final String READ_TOOL_NAME = "apiAccess.read";

    private PassThroughReadAnchor() {
    }

    /** Routing predicate for the dispatcher (built-in names aside, this is the only stamped tool). */
    public static boolean isPassThroughRead(String toolName) {
        return READ_TOOL_NAME.equals(toolName);
    }

    /**
 * Stamp the session segment into the nested {@code arguments} bag of a pass-through read.
 *
 * @param toolName FC tool name of this call (non-matching names never touch the payload)
 * @param argumentsJson model-emitted argument bag (must already be a JSON object — the
 * dispatcher validated it before routing)
 * @param projectId server-side conversation segment (null/zero = no segment, payload untouched)
 * @param mapper shared mapper (the dispatcher's own, never a second instance)
 * @return the possibly-stamped argument JSON, or the original string byte-identical
 */
    public static String stamp(String toolName, String argumentsJson, Long projectId, ObjectMapper mapper) {
        if (!isPassThroughRead(toolName) || projectId == null || projectId <= 0) {
            return argumentsJson;
        }
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return argumentsJson;
        }
        try {
            if (!(mapper.readTree(argumentsJson) instanceof ObjectNode root)) {
                return argumentsJson;
            }
            ObjectNode arguments = root.get("arguments") != null && root.get("arguments").isObject()
                    ? (ObjectNode) root.get("arguments")
                    : root.putObject("arguments");
            // Server truth wins over anything the model claimed inside the payload.
            arguments.put("projectId", projectId);
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            // Fail-soft like the dispatcher: a malformed bag rides on unchanged and the
            // core door refuses it readably instead of this seam throwing.
            return argumentsJson;
        }
    }
}
