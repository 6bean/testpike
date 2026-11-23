package com.sixbean.testpike.aiservice.gen.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts the honest "missing resource" declaration from a stage-2 draft
 * category that is absent from the injected project pool, it may report the
 * category and its intent in the optional top-level key
 * {@code missingResources[]}.
 * <p>Soft-signal posture: the key is optional, validation is never tightened
 * because of it and generation is never blocked by it. The extractor never
 * throws — a malformed or non-array draft folds to "no gaps" with the payload
 * bytes untouched. When gaps ARE declared the key is stripped before
 * persistence, so the stored action JSON keeps the legacy shape (the
 * RegenerationFeedback backward-compat posture: absent declaration = zero byte
 * drift; the declaration itself rides the process-event ledger instead, for
 * human review and the later retrieval-surface demand accounting).
 * <p>Bounds: at most 8 items and 120 characters per field are ledger-bound
 * (R57 — the event stream is an accumulating surface, a runaway model reply
 * must not bloat it).
 */
public final class ResourceGapDeclaration {

    /** The optional contract key taught at the resource-pool tail. */
    public static final String KEY = "missingResources";

    /** Ledger bound shared with the mechanical reconciliation (R57: the
     *  event stream accumulates, one runaway reply must not bloat it). */
    public static final int MAX_ITEMS = 8;
    private static final int MAX_FIELD_CHARS = 120;

    private ResourceGapDeclaration() {
    }

    /** Trim + 120-char clamp used by every ledger-bound declaration face
     *  (model self-report and server-side reconciliation share it). */
    public static String clampField(String text) {
        String t = text == null ? "" : text.trim();
        return t.length() <= MAX_FIELD_CHARS ? t : t.substring(0, MAX_FIELD_CHARS) + "…";
    }

    /** One declared gap: the category the model meant plus what it wanted it for. */
    public record GapItem(String category, String intent) {
    }

    /** {@code gaps} = declared entries (possibly empty); {@code json} = payload to persist. */
    public record Declaration(List<GapItem> gaps, String json) {
    }

    /**
     * Read and strip the declaration over a raw stage-2 draft.
     *
     * @param mapper  shared JSON mapper (never a per-call instance)
     * @param rawJson draft text already passed through the JSON extractor
     * @return declared gaps plus the payload to persist; any failure folds to
     *         empty gaps with the input bytes returned verbatim
     */
    public static Declaration extract(ObjectMapper mapper, String rawJson) {
        try {
            JsonNode root = mapper.readTree(rawJson);
            JsonNode declared = root.path(KEY);
            if (!declared.isArray() || declared.isEmpty()) {
                return new Declaration(List.of(), rawJson);
            }
            List<GapItem> gaps = new ArrayList<>();
            for (JsonNode item : declared) {
                String category = clampField(item.path("category").asText(""));
                String intent = clampField(item.path("intent").asText(""));
                if (category.isBlank() && intent.isBlank()) {
                    continue;
                }
                gaps.add(new GapItem(category.isBlank() ? "未注明类目" : category, intent));
                if (gaps.size() >= MAX_ITEMS) {
                    break;
                }
            }
            if (gaps.isEmpty()) {
                return new Declaration(List.of(), rawJson);
            }
            ((ObjectNode) root).remove(KEY);
            return new Declaration(List.copyOf(gaps), mapper.writeValueAsString(root));
        } catch (Exception soft) {
            return new Declaration(List.of(), rawJson);
        }
    }
}
