package com.sixbean.testpike.aiservice.doc.domain.component;

import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityLedger.Entry;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Storage door of tp_doc_extract_fidelity : one fidelity row
 * per (reference row, generation). A2 generalized the identity pair to
 * (ref_id, run_id) plus a task_type plane tag so the spec/qa doors book the
 * same account. Mirrors the coverage/stage ledger posture — writes stay
 * fail-soft (bookkeeping never fails the extraction run), a replayed
 * identical entry is a zero-write (R28), and a real change bumps the
 * revision instead of stacking duplicates.
 * every insert books a domain anchor alongside the neutral identity pair —
 * doc-extract/spec-extract rows anchor to tp_document (same cascade posture
 * as the retired unconditional FK, zero drift for the document plane), while
 * qa-gen rows bind ref_qa_job_id through the run token so the question-
 * generation account survives temporary-document reclamation instead of
 * cascading away. A rejected booking answers with the single-source readable
 * reject note ({@link ExtractFidelityLedger#rejectNote}), never a collapsed
 */
@Component
public final class ExtractFidelityLedgerStore {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ExtractFidelityLedgerStore.class);

    private static final String FIND_SQL =
            "SELECT ref_id, run_id, task_type, chunk_count, node_count, injected_tokens,"
                    + " source_tokens, retention_percent, distinct_chapters, item_count,"
                    + " anchored_item_count, anchor_rate_percent, estimate_tokens,"
                    + " usage_prompt_tokens, est_drift_percent, plain_json_tokens_per_item,"
                    + " billed_tokens_per_item, summary_calls, reconcile_calls,"
                    + " reused_source_tokens"
                    + " FROM tp_doc_extract_fidelity"
                    + " WHERE ref_id = :refId AND run_id = :runId";

    private static final String INSERT_HEAD =
            "INSERT INTO tp_doc_extract_fidelity"
                    + " (ref_id, run_id, task_type, chunk_count, node_count, injected_tokens,"
                    + " source_tokens, retention_percent, distinct_chapters, item_count,"
                    + " anchored_item_count, anchor_rate_percent, estimate_tokens,"
                    + " usage_prompt_tokens, est_drift_percent, plain_json_tokens_per_item,"
                    + " billed_tokens_per_item, summary_calls, reconcile_calls,"
                    + " reused_source_tokens,"
                    + " ref_document_id, ref_qa_job_id, revision)"
                    // plane anchors: the document planes keep the doc
                    // reference (FK rejects a dead document exactly like the
                    // retired unconditional constraint); qa-gen rows bind the
                    // job ledger row by run token when one exists, so the
                    // account outlives the temporary generation document.
                    + " VALUES (:refId, :runId, :taskType, :chunkCount, :nodeCount,"
                    + " :injectedTokens, :sourceTokens, :retentionPercent, :distinctChapters,"
                    + " :itemCount, :anchoredItemCount, :anchorRatePercent, :estimateTokens,"
                    + " :usagePromptTokens, :estDriftPercent, :plainJsonTokensPerItem,"
                    + " :billedTokensPerItem, :summaryCalls, :reconcileCalls,"
                    + " :reusedSourceTokens,"
                    + " (CASE WHEN :taskType = 'qa-gen' THEN NULL ELSE :refId END),"
                    + " (SELECT j.id FROM tp_qa_gen_job j WHERE j.run_token = :runId), 1)";

    /** failure-terminal face: the absent-only door lands the row only
 * when no book exists yet — a richer success row (or a concurrent book)
 * is never overwritten by failure bookkeeping. */
    private static final String INSERT_IF_ABSENT_SQL =
            INSERT_HEAD
                    + " ON CONFLICT (ref_id, run_id) DO NOTHING";

    private static final String UPSERT_SQL =
            INSERT_HEAD
                    + " ON CONFLICT (ref_id, run_id) DO UPDATE"
                    + " SET chunk_count = EXCLUDED.chunk_count,"
                    + " node_count = EXCLUDED.node_count,"
                    + " injected_tokens = EXCLUDED.injected_tokens,"
                    + " source_tokens = EXCLUDED.source_tokens,"
                    + " retention_percent = EXCLUDED.retention_percent,"
                    + " distinct_chapters = EXCLUDED.distinct_chapters,"
                    + " item_count = EXCLUDED.item_count,"
                    + " anchored_item_count = EXCLUDED.anchored_item_count,"
                    + " anchor_rate_percent = EXCLUDED.anchor_rate_percent,"
                    + " estimate_tokens = EXCLUDED.estimate_tokens,"
                    + " usage_prompt_tokens = EXCLUDED.usage_prompt_tokens,"
                    + " est_drift_percent = EXCLUDED.est_drift_percent,"
                    + " plain_json_tokens_per_item = EXCLUDED.plain_json_tokens_per_item,"
                    + " billed_tokens_per_item = EXCLUDED.billed_tokens_per_item,"
                    + " summary_calls = EXCLUDED.summary_calls,"
                    + " reconcile_calls = EXCLUDED.reconcile_calls,"
                    + " reused_source_tokens = EXCLUDED.reused_source_tokens,"
                    + " revision = tp_doc_extract_fidelity.revision + 1,"
                    + " updated_at = now()"
                    + " WHERE tp_doc_extract_fidelity.retention_percent"
                    + " IS DISTINCT FROM EXCLUDED.retention_percent"
                    + " OR tp_doc_extract_fidelity.injected_tokens"
                    + " IS DISTINCT FROM EXCLUDED.injected_tokens"
                    + " OR tp_doc_extract_fidelity.source_tokens"
                    + " IS DISTINCT FROM EXCLUDED.source_tokens"
                    + " OR tp_doc_extract_fidelity.item_count"
                    + " IS DISTINCT FROM EXCLUDED.item_count"
                    + " OR tp_doc_extract_fidelity.anchored_item_count"
                    + " IS DISTINCT FROM EXCLUDED.anchored_item_count"
                    + " OR tp_doc_extract_fidelity.anchor_rate_percent"
                    + " IS DISTINCT FROM EXCLUDED.anchor_rate_percent"
                    + " OR tp_doc_extract_fidelity.billed_tokens_per_item"
                    + " IS DISTINCT FROM EXCLUDED.billed_tokens_per_item"
                    + " OR tp_doc_extract_fidelity.reused_source_tokens"
                    + " IS DISTINCT FROM EXCLUDED.reused_source_tokens";

    private static final String UPDATE_SQL =
            "UPDATE tp_doc_extract_fidelity SET"
                    + " chunk_count = :chunkCount, node_count = :nodeCount,"
                    + " injected_tokens = :injectedTokens, source_tokens = :sourceTokens,"
                    + " retention_percent = :retentionPercent, distinct_chapters = :distinctChapters,"
                    + " item_count = :itemCount, anchored_item_count = :anchoredItemCount,"
                    + " anchor_rate_percent = :anchorRatePercent, estimate_tokens = :estimateTokens,"
                    + " usage_prompt_tokens = :usagePromptTokens, est_drift_percent = :estDriftPercent,"
                    + " plain_json_tokens_per_item = :plainJsonTokensPerItem,"
                    + " billed_tokens_per_item = :billedTokensPerItem,"
                    + " summary_calls = :summaryCalls, reconcile_calls = :reconcileCalls,"
                    + " reused_source_tokens = :reusedSourceTokens,"
                    + " revision = revision + 1, updated_at = now()"
                    + " WHERE ref_id = :refId AND run_id = :runId";

    private final NamedParameterJdbcTemplate jdbc;

    public ExtractFidelityLedgerStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Entry> ROW_MAPPER = (rs, rowNum) -> new Entry(
            rs.getLong("ref_id"), rs.getString("run_id"), rs.getString("task_type"),
            rs.getInt("chunk_count"), rs.getInt("node_count"),
            rs.getInt("injected_tokens"), rs.getInt("source_tokens"),
            rs.getDouble("retention_percent"), rs.getInt("distinct_chapters"),
            rs.getInt("item_count"), rs.getInt("anchored_item_count"),
            rs.getDouble("anchor_rate_percent"), rs.getInt("estimate_tokens"),
            rs.getInt("usage_prompt_tokens"), rs.getDouble("est_drift_percent"),
            rs.getInt("plain_json_tokens_per_item"), rs.getInt("billed_tokens_per_item"),
            rs.getInt("summary_calls"), rs.getInt("reconcile_calls"),
            rs.getInt("reused_source_tokens"));

    /** Stored fidelity row of one generation, or null (absent / door down). */
    public Entry find(long refId, String runId) {
        try {
            List<Entry> rows = jdbc.query(FIND_SQL,
                    new MapSqlParameterSource()
                            .addValue("refId", refId)
                            .addValue("runId", runId),
                    ROW_MAPPER);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException doorDown) {
            log.warn("提取忠实账读取门不可达，按无账继续 refId={} reason={}",
                    refId, doorDown.getMessage());
            return null;
        }
    }

    /**
 * Land one fidelity row (fail-soft: an unreachable door answers false and
 * never propagates — the extraction run must not die on bookkeeping).
 * : the refusal itself is never collapsed — the WARN names plane,
 * reference row, run token and root cause through the single-source note.
 *
 * @return true when the row was created or genuinely updated, false on a
 * zero-write replay or a door outage
 */
    public boolean upsertIfChanged(Entry entry) {
        try {
            Entry existing = find(entry.refId(), entry.runId());
            if (existing == null) {
                jdbc.update(UPSERT_SQL, params(entry));
                return true;
            }
            if (existing.equals(entry)) {
                return false;
            }
            jdbc.update(UPDATE_SQL, params(entry));
            return true;
        } catch (RuntimeException doorDown) {
            log.warn(ExtractFidelityLedger.rejectNote(entry.taskType(),
                    entry.refId(), entry.runId(), doorDown));
            return false;
        }
    }

    /**
 * : land the failure-terminal row only while the generation has no
 * book at all (fail-soft like the upsert door). A run that already landed
 * its success-row face keeps its richer numbers — the failure book must
 * never downgrade them.
 *
 * @return true when the zero-item row was created, false when a row was
 * already present or the door is unreachable
 */
    public boolean recordIfAbsent(Entry entry) {
        try {
            if (find(entry.refId(), entry.runId()) != null) {
                return false;
            }
            return jdbc.update(INSERT_IF_ABSENT_SQL, params(entry)) > 0;
        } catch (RuntimeException doorDown) {
            log.warn(ExtractFidelityLedger.rejectNote(entry.taskType(),
                    entry.refId(), entry.runId(), doorDown));
            return false;
        }
    }

    private static MapSqlParameterSource params(Entry e) {
        return new MapSqlParameterSource()
                .addValue("refId", e.refId())
                .addValue("runId", e.runId())
                .addValue("taskType", e.taskType())
                .addValue("chunkCount", e.chunkCount())
                .addValue("nodeCount", e.nodeCount())
                .addValue("injectedTokens", e.injectedTokens())
                .addValue("sourceTokens", e.sourceTokens())
                .addValue("retentionPercent", e.retentionPercent())
                .addValue("distinctChapters", e.distinctChapters())
                .addValue("itemCount", e.itemCount())
                .addValue("anchoredItemCount", e.anchoredItemCount())
                .addValue("anchorRatePercent", e.anchorRatePercent())
                .addValue("estimateTokens", e.estimateTokens())
                .addValue("usagePromptTokens", e.usagePromptTokens())
                .addValue("estDriftPercent", e.estDriftPercent())
                .addValue("plainJsonTokensPerItem", e.plainJsonTokensPerItem())
                .addValue("billedTokensPerItem", e.billedTokensPerItem())
                .addValue("summaryCalls", e.summaryCalls())
                .addValue("reconcileCalls", e.reconcileCalls())
                .addValue("reusedSourceTokens", e.reusedSourceTokens());
    }
}
