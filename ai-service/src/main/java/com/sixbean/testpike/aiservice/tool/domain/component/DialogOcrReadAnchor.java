package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Server-side conversation anchoring for the two dialog OCR read tools
 * : {@code ocr.text_extract} (character post) and
 * {@code ocr.grid_read} (grid post) consume one attached chat image and read
 * it back through the project/owner doors, so the request body must carry the
 * trio {@code chatSessionId}/{@code projectId}/{@code operatorId}. The model
 * only ever writes {@code objectKey}; the trio is stamped here — the same
 * posture as {@link PassThroughReadAnchor} on the nested pass-through bag,
 * lifted onto the flat request DTO (see {@code OcrDialogToolRequest}).
 *
 * <p>Unlike the pass-through stamp this one is UNCONDITIONAL on its two tool
 * names: a model claim is always overwritten by the server value, and a
 * missing server anchor (null/zero — the async generation link never carries
 * a conversation) strips the claimed field instead of letting it ride on.
 * The doors downstream refuse fail-closed on any missing anchor, so a forged
 * session id can neither survive the seam nor reach the ledger scan.
 *
 * <p>Everything else rides byte-identical: non-matching tool names, blank or
 * non-object payloads and malformed JSON leave the string untouched (the
 * dispatcher validated JSON shape before routing; a broken bag fails soft at
 * the door, not here).
 */
public final class DialogOcrReadAnchor {

    /** tp_tool row name of the character post (engine lines + red-mark list). */
    public static final String TEXT_TOOL_NAME = "ocr.text_extract";

    /** tp_tool row name of the grid post (shared GridAssembler clustering). */
    public static final String GRID_TOOL_NAME = "ocr.grid_read";

    private DialogOcrReadAnchor() {
    }

    /** Routing predicate: exact registry names only (prefix sharing is not identity). */
    public static boolean isDialogOcrRead(String toolName) {
        return TEXT_TOOL_NAME.equals(toolName) || GRID_TOOL_NAME.equals(toolName);
    }

    /**
 * Stamp (or strip) the conversation trio on a dialog OCR read call.
 *
 * @param toolName FC tool name of this call (non-matching = untouched)
 * @param argumentsJson model-emitted argument bag (flat object; the
 * dispatcher validated its JSON shape before routing)
 * @param chatSessionId server conversation segment (null/zero = strip claim)
 * @param projectId server project segment (null/zero = strip claim)
 * @param operatorId acting user from the dispatch carrier (null/zero = strip)
 * @param mapper shared mapper (the dispatcher's own, never a second instance)
 * @return the re-written argument JSON, or the original string byte-identical
 */
    public static String stamp(String toolName, String argumentsJson, Long chatSessionId,
                               Long projectId, Long operatorId, ObjectMapper mapper) {
        if (!isDialogOcrRead(toolName)) {
            return argumentsJson;
        }
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return argumentsJson;
        }
        try {
            if (!(mapper.readTree(argumentsJson) instanceof ObjectNode root)) {
                return argumentsJson;
            }
            putOrRemove(root, "chatSessionId", chatSessionId);
            putOrRemove(root, "projectId", projectId);
            putOrRemove(root, "operatorId", operatorId);
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            // Fail-soft like the pass-through stamp: a malformed bag rides on
            // unchanged and the door refuses it readably instead of this
            // seam throwing into the ReAct loop.
            return argumentsJson;
        }
    }

    private static void putOrRemove(ObjectNode node, String field, Long serverValue) {
        if (serverValue != null && serverValue > 0) {
            node.put(field, serverValue);
        } else {
            node.remove(field);
        }
    }
}
