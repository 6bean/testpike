package com.sixbean.testpike.core.linkage.domain.service.impl;

import com.sixbean.testpike.core.document.domain.component.DocChangeAnalysisStatus;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType;
import com.sixbean.testpike.core.linkage.domain.service.LinkageImpactConsumptionService;
import com.sixbean.testpike.core.spec.domain.component.SpecChangeAnalysisStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Ledger write behind the confirmation door (see the interface for the
 * contract). One keyed idempotent UPDATE is the whole mechanism: the settle
 * only fires while consumed_at is still NULL, so a replay never restamps a
 * settled row (no phantom consume facts, same posture as the empty-save
 * gate) and the panel debt list drains as cards get accepted.
 * <p>Depends only on the two ledger tables it reads — never on an aichat
 * bean — so wiring it into the draft door cannot close a Spring cycle with
 * the mint chain (linkage → aichat), which stays the only direction.
 */
@Service
public class LinkageImpactConsumptionServiceImpl implements LinkageImpactConsumptionService {

    private static final Logger log =
            LoggerFactory.getLogger(LinkageImpactConsumptionServiceImpl.class);

    /** V515 semantics: 4=consumed (accepted or acknowledged). */
    private static final int IMPACT_CONSUMED = 4;

    /** V515 open states a disposition may drain: 1=stale mark, 2=draft pending, 3=disclosed. */
    private static final List<Integer> IMPACT_ARMED_STATES = List.of(1, 2, 3);

    private final NamedParameterJdbcTemplate named;

    public LinkageImpactConsumptionServiceImpl(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    @Override
    @Transactional
    public boolean consumeForDraft(long draftId, long operatorId) {
        List<String> keyed = named.queryForList(
                "SELECT context_json->>'linkageImpactId' FROM tp_ai_chat_draft WHERE id = :draftId",
                new MapSqlParameterSource("draftId", draftId), String.class);
        if (keyed.isEmpty() || keyed.get(0) == null || keyed.get(0).isBlank()) {
            return false;
        }
        long impactId;
        try {
            impactId = Long.parseLong(keyed.get(0).trim());
        } catch (NumberFormatException ex) {
            // A corrupt anchor is data, not a landing failure: the human's
            // confirmed write stays, the ledger row honestly waits in the
            // panel with a loud line in the log ledger for triage.
            log.warn("draft {} keys linkage impact \"{}\" which is not a ledger id — "
                    + "the impact row stays pending", draftId, keyed.get(0));
            return false;
        }
        List<Map<String, Object>> upstreamRows = named.getJdbcTemplate().queryForList(
                "SELECT upstream_object_type, upstream_object_id FROM tp_linkage_impact WHERE id = ?",
                impactId);
        Map<String, Object> upstream = upstreamRows.isEmpty() ? null : upstreamRows.get(0);
        if (upstream == null || upstream.get("upstream_object_id") == null) {
            log.warn("draft {} keys linkage impact {} which is no longer in the ledger — "
                    + "nothing was drained from the analysis queues", draftId, impactId);
            return false;
        }
        boolean stamped = named.update(
                "UPDATE tp_linkage_impact"
                        + " SET status = :consumed, consumed_at = now(), annotated_at = now()"
                        + " WHERE id = :impactId AND consumed_at IS NULL",
                new MapSqlParameterSource("consumed", IMPACT_CONSUMED)
                        .addValue("impactId", impactId)) == 1;
        drainAnalysisQueueRow(String.valueOf(upstream.get("upstream_object_type")),
                ((Number) upstream.get("upstream_object_id")).longValue(), draftId, operatorId);
        return stamped;
    }

    @Override
    @Transactional
    public int settleImpactsForUpstream(String upstreamObjectType, long upstreamObjectId) {
        if (upstreamObjectType == null || upstreamObjectType.isBlank()) {
            return 0;
        }
        return named.update(
                "UPDATE tp_linkage_impact"
                        + " SET status = :consumed, consumed_at = now()"
                        + " WHERE upstream_object_type = :type AND upstream_object_id = :objectId"
                        + " AND status IN (:armed) AND consumed_at IS NULL",
                new MapSqlParameterSource("consumed", IMPACT_CONSUMED)
                        .addValue("type", upstreamObjectType.trim())
                        .addValue("objectId", upstreamObjectId)
                        .addValue("armed", IMPACT_ARMED_STATES));
    }

    /**
     * One verdict, no second account: accepting the card also disposes the
     * change-analysis queue row produced by the same upstream save. Only the
     * two registered analysis families are touched, and only while the row
     * still waits for review (pending-only flip mirrors each queue's own
     * guard); the note names the card so the reviewer can trace the verdict
     * back to the draft that landed it.
     */
    private void drainAnalysisQueueRow(String upstreamFamily, long upstreamObjectId,
                                       long draftId, long operatorId) {
        String note = "已在联动草案 #" + draftId + " 接受时同步处置";
        if (ObjectChangedEventType.API_SPEC.objectType().equals(upstreamFamily)) {
            named.update(
                    "UPDATE tp_api_spec_change_analysis"
                            + " SET status = :handled, handled_by = :operatorId,"
                            + "     handled_at = now(), handle_note = :note"
                            + " WHERE new_spec_id = :objectId AND status = :pending",
                    queueParams(operatorId, note, upstreamObjectId,
                            SpecChangeAnalysisStatus.HANDLED, SpecChangeAnalysisStatus.PENDING));
        } else if (ObjectChangedEventType.DOC.objectType().equals(upstreamFamily)) {
            named.update(
                    "UPDATE tp_doc_change_analysis"
                            + " SET status = :handled, handled_by = :operatorId,"
                            + "     handled_at = now(), handle_note = :note"
                            + " WHERE new_document_id = :objectId AND status = :pending",
                    queueParams(operatorId, note, upstreamObjectId,
                            DocChangeAnalysisStatus.HANDLED, DocChangeAnalysisStatus.PENDING));
        }
        // Any other upstream family has no change-analysis queue: zero touch.
    }

    private MapSqlParameterSource queueParams(long operatorId, String note, long upstreamObjectId,
                                              int handled, int pending) {
        return new MapSqlParameterSource()
                .addValue("handled", handled)
                .addValue("pending", pending)
                .addValue("operatorId", operatorId)
                .addValue("note", note)
                .addValue("objectId", upstreamObjectId);
    }
}
