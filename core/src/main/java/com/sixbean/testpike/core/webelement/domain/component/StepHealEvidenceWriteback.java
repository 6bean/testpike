package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.webelement.api.dto.StepHealEvidenceInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Direct-heal evidence writeback for the Page Element Library self-heal
 * ledger (03 protocol 2.4 trailing key stepHealEvidence). The executor
 * only records what happened during a heal; this component is the single
 * place that lands those rows into tp_exec_step_heal_evidence (V554) —
 * the audit trail behind a self-healed run (executor records, core stores
 * verbatim, no scoring on either side of the ledger columns).
 * <p>Row shape contract: the nested fromAnchor/usedAnchor halves flatten
 * into (type, value) column pairs; the type and viewport columns are
 * clamped to their declared width so one oversized observation can never
 * fail the whole batch and take the relay chain with it.
 * <p>Hook contract (same discipline as WebElementAnchorStatWriteback):
 * called at the tail of ExecTaskServiceImpl.submitResult after the
 * terminal UPDATE, in the same transaction; submitResult's idempotent
 * early return on non-RUNNING tasks protects the hook from duplicate
 * rewrites, and any failure here is logged and swallowed — an evidence
 * problem must never break the result relay chain. R57: rows are bounded
 * by the task FK (ON DELETE CASCADE), so task retention cleanup drops the
 * evidence with its task; element_id carries no FK on purpose — a ledger
 * element retired after the run must not destroy historical evidence.
 */
@Component
public class StepHealEvidenceWriteback {

    private static final Logger log = LoggerFactory.getLogger(StepHealEvidenceWriteback.class);

    /** Width of the *_anchor_type / viewport columns (V554 DDL). */
    private static final int SHORT_COLUMN_WIDTH = 32;

    private static final String INSERT_SQL = """
            INSERT INTO tp_exec_step_heal_evidence
                    (task_id, element_id, step_no, healed, first_anchor_failed,
                     from_anchor_type, from_anchor_value,
                     used_anchor_type, used_anchor_value,
                     viewport, ledger_revision, similarity_score)
            VALUES (:taskId, :elementId, :stepNo, :healed, :firstAnchorFailed,
                    :fromAnchorType, :fromAnchorValue,
                    :usedAnchorType, :usedAnchorValue,
                    :viewport, :ledgerRevision, :similarityScore)
            """;

    private final ObjectMapper objectMapper;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    public StepHealEvidenceWriteback(ObjectMapper objectMapper,
                                     NamedParameterJdbcTemplate namedJdbcTemplate) {
        this.objectMapper = objectMapper;
        this.namedJdbcTemplate = namedJdbcTemplate;
    }

    /** Result-relay tail hook: see class contract. Never throws. */
    public void onResultSubmitted(ExecTaskInfo task, ExecResultInput input) {
        if (task.taskType() != 1) {
            return;
        }
        // Trailing-key precedent: absent key = legacy relay, zero drift.
        if (input.stepHealEvidence() == null) {
            return;
        }
        try {
            write(task, input);
        } catch (RuntimeException hookFailure) {
            log.warn("step heal evidence writeback skipped for task {}: {}",
                    task.id(), hookFailure.getMessage());
        }
    }

    private void write(ExecTaskInfo task, ExecResultInput input) {
        List<StepHealEvidenceInput> rows = convertRows(input.stepHealEvidence());
        if (rows.isEmpty()) {
            return;
        }
        List<MapSqlParameterSource> batch = new ArrayList<>();
        for (StepHealEvidenceInput row : rows) {
            batch.add(new MapSqlParameterSource()
                    .addValue("taskId", task.id())
                    .addValue("elementId", row.elementId())
                    .addValue("stepNo", row.stepNo())
                    .addValue("healed", row.healed())
                    .addValue("firstAnchorFailed", row.firstAnchorFailed())
                    .addValue("fromAnchorType", clamp(anchorSide(row.fromAnchor(), true)))
                    .addValue("fromAnchorValue", anchorSide(row.fromAnchor(), false))
                    .addValue("usedAnchorType", clamp(anchorSide(row.usedAnchor(), true)))
                    .addValue("usedAnchorValue", anchorSide(row.usedAnchor(), false))
                    .addValue("viewport", clamp(row.viewport()))
                    .addValue("ledgerRevision", row.ledgerRevision())
                    .addValue("similarityScore", row.similarityScore()));
        }
        namedJdbcTemplate.batchUpdate(INSERT_SQL, batch.toArray(MapSqlParameterSource[]::new));
    }

    /**
     * Convert the raw evidence plane (executor JSON array or already-parsed
     * list) into typed rows. An un-parseable plane yields no rows — the
     * relay itself stays intact (fail-soft evidence, same shape as the
     * anchor-stat hook).
     */
    private List<StepHealEvidenceInput> convertRows(Object raw) {
        try {
            if (raw instanceof String json && !json.isBlank()) {
                return objectMapper.readValue(json, new TypeReference<>() {
                });
            }
            return objectMapper.convertValue(raw, new TypeReference<>() {
            });
        } catch (Exception malformed) {
            return List.of();
        }
    }

    /** side=true reads the anchor class, side=false the locator expression. */
    private static String anchorSide(WebElementAnchorInput anchor, boolean side) {
        if (anchor == null) {
            return null;
        }
        return side ? anchor.type() : anchor.value();
    }

    /** Column-width guard: over-short values pass through untouched. */
    private static String clamp(String value) {
        if (value == null || value.length() <= SHORT_COLUMN_WIDTH) {
            return value;
        }
        return value.substring(0, SHORT_COLUMN_WIDTH);
    }
}
