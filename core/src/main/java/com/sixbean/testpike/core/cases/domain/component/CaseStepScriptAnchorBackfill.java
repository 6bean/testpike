package com.sixbean.testpike.core.cases.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Script write-back anchor backfill .
 * <p>Why this exists: the live generation window measured steps=6 anchored=0 —
 * stage-2/off-site write-back lands a script whose actions reference page
 * elements through the {{ELEM:name}} dialect, but that per-step evidence never
 * reached tp_case_step.anchor, so the review UI still could not answer "which
 * page element does this step touch". This component rides the single core
 * save door (ScriptServiceImpl.saveInternal), so every write chain — AI
 * stage-2, off-site write-back, repair landing, manual script edit — leaves
 * the same evidence without a second implementation (R40).
 * <p>Merge posture: element refs are UNIONED into the step's existing anchor
 * and the requirement-block source evidence (documentId/chunkIds stamped by
 * chain ①) is preserved; the kind label only upgrades from "source" to "elem"
 * when real element refs arrive. Steps whose script actions carry no refs are
 * left byte-identical, and an identical action document replaying through the
 * door writes nothing (R28, canonical comparison).
 * <p>Fail-soft contract (WE candidate ledger posture at the same door): any
 * parse or update failure is one WARN line and NEVER blocks or rolls back the
 * save — anchor evidence is enrichment, not a write gate.
 */
@Component
public class CaseStepScriptAnchorBackfill {

    private static final Logger log = LoggerFactory.getLogger(CaseStepScriptAnchorBackfill.class);

    private final JdbcTemplate jdbcTemplate;
    private final CaseStepAnchorNormalizer normalizer;
    private final ObjectMapper objectMapper;

    public CaseStepScriptAnchorBackfill(JdbcTemplate jdbcTemplate,
                                        CaseStepAnchorNormalizer normalizer,
                                        ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.normalizer = normalizer;
        this.objectMapper = objectMapper;
    }

    /**
     * Extract the element refs each case step's script actions carry.
     * <p>Numbering: an explicit numeric {@code stepNo} on a step object wins;
     * otherwise the 1-based position in the top-level steps array. Nested
     * branch arrays (steps/elseSteps) inherit the enclosing step number, so a
     * grouped document merges its refs onto the same ledger row instead of
     * inventing phantom steps.
     *
     * @param actionJson saved action document text; null/blank = no evidence
     * @return stepNo → ordered distinct element names (steps without refs absent)
     */
    public Map<Integer, List<String>> refsByStep(String actionJson) {
        if (actionJson == null || actionJson.isBlank()) {
            return Map.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(actionJson);
        } catch (Exception unparsable) {
            throw new IllegalArgumentException("action document is not valid JSON", unparsable);
        }
        LinkedHashMap<Integer, LinkedHashSet<String>> acc = new LinkedHashMap<>();
        JsonNode steps = root.path("steps");
        if (steps.isArray()) {
            for (int i = 0; i < steps.size(); i++) {
                JsonNode step = steps.get(i);
                if (!step.isObject()) {
                    continue;
                }
                int stepNo = step.path("stepNo").isNumber() ? step.path("stepNo").asInt(i + 1) : i + 1;
                LinkedHashSet<String> names = new LinkedHashSet<>();
                collectText(step, names);
                if (!names.isEmpty()) {
                    acc.computeIfAbsent(stepNo, k -> new LinkedHashSet<>()).addAll(names);
                }
            }
        }
        LinkedHashMap<Integer, List<String>> out = new LinkedHashMap<>();
        acc.forEach((no, names) -> out.put(no, List.copyOf(names)));
        return out;
    }

    /**
     * Backfill tp_case_step.anchor for one case from its saved action document.
     *
     * @return number of step rows actually rewritten (0 = nothing changed)
     */
    public int backfill(long caseId, String actionJson) {
        try {
            Map<Integer, List<String>> refsByStep = refsByStep(actionJson);
            if (refsByStep.isEmpty()) {
                return 0;
            }
            Integer caseVersion = jdbcTemplate.query(
                    "SELECT version FROM tp_test_case WHERE id = ? AND deleted = 0",
                    rs -> rs.next() ? rs.getInt(1) : null, caseId);
            if (caseVersion == null) {
                return 0;
            }
            List<StepRow> rows = jdbcTemplate.query(
                    "SELECT id, step_no, anchor::text AS anchor FROM tp_case_step "
                            + "WHERE case_id = ? AND case_version = ?",
                    (rs, n) -> new StepRow(rs.getLong("id"), rs.getInt("step_no"), rs.getString("anchor")),
                    caseId, caseVersion);
            int changed = 0;
            for (StepRow row : rows) {
                List<String> refs = refsByStep.get(row.stepNo());
                if (refs == null || refs.isEmpty()) {
                    continue;
                }
                String merged = normalizer.mergeElementAnchor(row.anchor(), refs);
                if (sameAnchor(row.anchor(), merged)) {
                    continue;
                }
                jdbcTemplate.update("UPDATE tp_case_step SET anchor = ?::jsonb, updated_at = now() "
                        + "WHERE id = ?", merged, row.id());
                changed++;
            }
            if (changed > 0) {
                log.info("step anchor backfill landed caseId={} version={} rows={}",
                        caseId, caseVersion, changed);
            }
            return changed;
        } catch (Exception soft) {
            log.warn("step anchor backfill skipped (save never blocked) caseId={}: {}",
                    caseId, soft.toString());
            return 0;
        }
    }

    /** One current-version step row. */
    private record StepRow(long id, int stepNo, String anchor) {
    }

    /**
     * Order-insensitive anchor equality: jsonb::text round-trips reorder object
     * keys, so string comparison would falsely report a changed anchor on every
     * identical replay (R28). Parsed JsonNode equality compares fields without
     * caring about key order; array order (chunkIds) stays significant.
     */
    private boolean sameAnchor(String existing, String merged) {
        try {
            JsonNode a = objectMapper.readTree(existing == null || existing.isBlank() ? "null" : existing);
            JsonNode b = objectMapper.readTree(merged == null || merged.isBlank() ? "null" : merged);
            return Objects.equals(a, b);
        } catch (Exception unparsable) {
            return Objects.equals(existing, merged);
        }
    }

    /** Gather every {{ELEM:name}} token under a step object (nested branches included). */
    private void collectText(JsonNode node, LinkedHashSet<String> names) {
        if (node.isTextual()) {
            names.addAll(normalizer.elementRefs(node.asText()));
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectText(child, names);
            }
        } else if (node.isObject()) {
            node.forEach(child -> collectText(child, names));
        }
    }

}
