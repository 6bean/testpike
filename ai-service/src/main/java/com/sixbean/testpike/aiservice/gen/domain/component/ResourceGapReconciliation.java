package com.sixbean.testpike.aiservice.gen.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.domain.component.ResourceGapDeclaration.GapItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side mechanical gap audit ( slice-c addendum, ruling "c extra
 * live audit" 2026-11-12): the model reliably CONSTRUCTS structured
 * placeholders for resources it lacks ({@code {{ENV:SMS_GATEWAY_SEND_URL}}}
 * with a self-describing step text, proven on live tasks 2908/2909) while
 * its voluntary {@code missingResources[]} reply missed both rounds — so the
 * declaration trigger moves off model self-discipline onto a deterministic
 * server check, kept as a UNION with whatever the model does report.
 * <p>Extraction surface (structured slots only — never full-text tokenizing,
 * per the frozen shape): every {@code {{ENV:KEY}}}/{@code {{DATA:KEY}}}
 * placeholder key inside the converged draft plus the top-level env block's
 * ENV and DATA values. Each distinct reference is scored against the FULL
 * pool rows through {@link PoolRowRelevanceScoring#textMatchScore} — the
 * slice-b coverage face, so "covered" means the row's full name or a row
 * token literally appears in the reference (the +1 usage hint must not fake
 * coverage for a busy but unrelated row).
 * the enclosing step description (120-char clamp, 8-item bound — shared
 * bounds with {@link ResourceGapDeclaration}, R57 accumulating-surface rule).
 * Honest no-padding posture: no placeholders = nothing declared; a malformed
 * draft or an unavailable pool read folds to silence — this audit never
 * throws, never blocks generation and never rewrites the payload.
 */
public final class ResourceGapReconciliation {

    /** Category prefix on the ledger: the reference names an env/data slot
 */
    public static final String CATEGORY_PREFIX = "环境占位 ";

    /** ENV/DATA placeholder slots (structured, brace-delimited). */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*(?:ENV|DATA)\\s*:\\s*([^{}]+?)\\s*\\}\\}");

    private ResourceGapReconciliation() {
    }

    /**
     * Reconcile one converged draft against the full pool.
     *
     * @param mapper   shared JSON mapper (never a per-call instance)
     * @param draftJson draft text (already JSON-extracted)
     * @param poolRows every pool row the project has (env/factory/collection,
     *                 NOT the cap-clipped prompt list — a reference may ride
     *                 a row that fell out of the prompt window and is then
     *                 legitimately not a gap)
     * @return mechanical gap declarations (possibly empty), bounded and
     *         deduplicated; any read failure folds to an empty list
     */
    public static List<GapItem> reconcile(ObjectMapper mapper, String draftJson,
                                         List<Map<String, Object>> poolRows) {
        if (poolRows == null || poolRows.isEmpty()) {
            // Pool unreadable/absent = indeterminate, and an indeterminate
            // audit declares nothing (fail-soft, never a wall of false gaps).
            return List.of();
        }
        Map<String, GapItem> gaps = new LinkedHashMap<>();
        try {
            JsonNode root = mapper.readTree(draftJson);
            if (root == null || !root.isObject()) {
                return List.of();
            }
            Map<String, String> references = new LinkedHashMap<>();
            Map<String, String> referenceDescs = new LinkedHashMap<>();
            collectReferences(root, "", references, referenceDescs);
            collectEnvBlockReferences(root, references, referenceDescs);
            for (Map.Entry<String, String> ref : references.entrySet()) {
                String lower = ref.getKey();
                boolean covered = false;
                for (Map<String, Object> row : poolRows) {
                    if (PoolRowRelevanceScoring.textMatchScore(lower, row) > 0) {
                        covered = true;
                        break;
                    }
                }
                if (!covered && gaps.size() < ResourceGapDeclaration.MAX_ITEMS) {
                    gaps.put(lower, new GapItem(CATEGORY_PREFIX + ref.getValue(),
                            ResourceGapDeclaration.clampField(
                                    referenceDescs.getOrDefault(lower, ""))));
                }
            }
        } catch (Exception soft) {
            return List.of();
        }
        return List.copyOf(gaps.values());
    }

    /**
     * Union of the model's self-reported gaps and the server's mechanical
     * ones, deduplicated on the category text with the MODEL wording winning
     * a shared category, bounded by the same 8-item ledger cap.
     */
    public static List<GapItem> union(List<GapItem> modelGaps, List<GapItem> serverGaps) {
        Map<String, GapItem> merged = new LinkedHashMap<>();
        addBounded(merged, modelGaps);
        addBounded(merged, serverGaps);
        return List.copyOf(merged.values());
    }

    private static void addBounded(Map<String, GapItem> sink, List<GapItem> items) {
        if (items == null) {
            return;
        }
        for (GapItem item : items) {
            if (item == null || item.category() == null || item.category().isBlank()) {
                continue;
            }
            if (sink.size() >= ResourceGapDeclaration.MAX_ITEMS) {
                return;
            }
            sink.putIfAbsent(item.category(), item);
        }
    }

    /** Placeholder slots in every string leaf, tagged with the nearest
     *  enclosing step description (empty when none). Map keys are the
     *  lowered reference (dedup face); values keep the authored casing. */
    private static void collectReferences(JsonNode node, String desc,
                                          Map<String, String> out,
                                          Map<String, String> descs) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode d = node.get("description");
            final String current = d != null && d.isTextual() && !d.asText().isBlank()
                    ? d.asText() : desc;
            node.fields().forEachRemaining(entry -> collectReferences(entry.getValue(),
                    current, out, descs));
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectReferences(child, desc, out, descs);
            }
        } else if (node.isTextual()) {
            Matcher m = PLACEHOLDER.matcher(node.asText());
            while (m.find()) {
                remember(out, descs, m.group(1), desc);
            }
        }
    }

    /** The env block's ENV/DATA values are selections of pool resources —
     *  a value naming nothing in the pool is dangling just like a placeholder.
     *  PACK is an industry-package reference, out of this audit's scope. */
    private static void collectEnvBlockReferences(JsonNode root, Map<String, String> out,
                                                  Map<String, String> descs) {
        JsonNode env = root.path("env");
        if (!env.isObject()) {
            return;
        }
        for (String slot : new String[]{"ENV", "DATA"}) {
            JsonNode value = env.path(slot);
            if (value.isTextual() && !value.asText().isBlank()) {
                remember(out, descs, value.asText(), "");
            }
        }
    }

    private static void remember(Map<String, String> out, Map<String, String> descs,
                                 String reference, String desc) {
        String key = reference == null ? "" : reference.trim();
        if (key.isEmpty()) {
            return;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        out.putIfAbsent(lower, key);
        descs.putIfAbsent(lower, desc);
    }
}
