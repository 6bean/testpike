package com.sixbean.testpike.core.document.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.component.DraftSeqAllocator;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.common.component.dedup.DedupCandidate;
import com.sixbean.testpike.core.document.api.dto.ExtractionItem;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Update-proposal minting for re-extracted homonyms.
 *
 * <p>When a document re-extraction proposes a requirement title the project
 * already carries (the shared dedup gate's L1/L2 refusal), the item no longer
 * lands silently: it becomes an anchored update-proposal draft card in the
 * document's deep-linked dialog session, so the human one-click confirmation
 * door can apply it as a versioned update. Nothing is written to the
 * requirement table here - the confirmation chain stays the only apply path.
 *
 * <p>Anchoring mirrors the change-disposition pre-generation precedent: one
 * deep-link session per (operator, project, document) carrying
 * {@code context_json.documentId}; the card carries the existing requirement
 * as target and its current version as the optimistic-lock baseline.
 *
 * <p>Double anchor keeps re-confirm idempotent: a pending card for the same
 * target, or a sealed card of the exact same payload (already applied or
 * voided by the human), blocks minting a second card.
 */
@Service
public class ExtractionUpdateProposalService {

    /** Outcome of one mint attempt, counted by the confirm loop. */
    public enum Outcome {
        /** A new pending card landed. */
        MINTED,
        /** A card for this target/payload is already in the ledger (count as skipped). */
        COVERED,
        /** The anchored requirement vanished mid-window (soft-deleted), honestly skipped. */
        NO_TARGET
    }

    private static final int SESSION_SOURCE_DEEP_LINK = 3;
    private static final int DRAFT_STATUS_PENDING = 1;
    private static final String DRAFT_OBJECT_REQUIREMENT_WRITE = "REQUIREMENT_WRITE";
    private static final String DRAFT_SCHEMA_VERSION = "1";

    private final ChatSessionService sessionService;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper objectMapper;
    private final DraftSeqAllocator draftSeqAllocator;

    public ExtractionUpdateProposalService(ChatSessionService sessionService,
                                           NamedParameterJdbcTemplate named,
                                           ObjectMapper objectMapper,
                                           DraftSeqAllocator draftSeqAllocator) {
        this.sessionService = sessionService;
        this.named = named;
        this.objectMapper = objectMapper;
        this.draftSeqAllocator = draftSeqAllocator;
    }

    /**
     * Mints (or skips) the anchored update-proposal card for one homonym hit.
     *
     * @param item     the preview item whose content becomes the proposal payload
     * @param matched  the existing requirement named by the dedup gate
     */
    public Outcome propose(long operatorId, long projectId, long documentId,
                           ExtractionItem item, DedupCandidate matched) {
        int baseVersion = currentVersion(matched.id());
        if (baseVersion < 0) {
            return Outcome.NO_TARGET;
        }
        String payloadJson = payloadOf(item);
        long sessionId = anchoredSessionId(operatorId, projectId, documentId);
        if (alreadyCovered(sessionId, matched.id(), payloadJson)) {
            return Outcome.COVERED;
        }
        named.update("INSERT INTO tp_ai_chat_draft (session_id, object_type, target_id,"
                        + " base_version, schema_version, summary, payload_json, status, seq)"
                        + " VALUES (:sessionId, :objectType, :targetId, :baseVersion,"
                        + " :schemaVersion, :summary, :payloadJson, :status, :seq)",
                new MapSqlParameterSource()
                        .addValue("sessionId", sessionId)
                        .addValue("objectType", DRAFT_OBJECT_REQUIREMENT_WRITE)
                        .addValue("targetId", matched.id())
                        .addValue("seq", draftSeqAllocator.nextSeq(matched.id()))
                        .addValue("baseVersion", baseVersion)
                        .addValue("schemaVersion", DRAFT_SCHEMA_VERSION)
                        .addValue("summary", summaryOf(item, matched))
                        .addValue("payloadJson", payloadJson)
                        .addValue("status", DRAFT_STATUS_PENDING));
        return Outcome.MINTED;
    }

    /** Current optimistic-lock baseline; -1 when the row is gone (deleted out of window). */
    private int currentVersion(long requirementId) {
        List<Integer> found = named.queryForList(
                "SELECT version FROM tp_requirement WHERE id = :id AND deleted = 0",
                new MapSqlParameterSource("id", requirementId), Integer.class);
        return found.isEmpty() ? -1 : found.get(0);
    }

    /** The one deep-linked session for this document, created on first use. */
    private long anchoredSessionId(long operatorId, long projectId, long documentId) {
        List<Long> found = named.queryForList(
                "SELECT id FROM tp_ai_chat_session WHERE user_id = :operatorId"
                        + " AND project_id = :projectId AND source = :source"
                        + " AND context_json->>'documentId' = :documentId"
                        + " ORDER BY id LIMIT 1",
                new MapSqlParameterSource()
                        .addValue("operatorId", operatorId)
                        .addValue("projectId", projectId)
                        .addValue("source", SESSION_SOURCE_DEEP_LINK)
                        .addValue("documentId", String.valueOf(documentId)),
                Long.class);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        // Blank title rides the auto-naming on the first dialog turn.
        ChatSessionInfo created = sessionService.create(projectId, operatorId, "",
                SESSION_SOURCE_DEEP_LINK, "{\"documentId\":" + documentId + "}");
        return created.id();
    }

    /** Double anchor: a pending card for the same target, or a sealed identical payload. */
    private boolean alreadyCovered(long sessionId, long targetId, String payloadJson) {
        Integer pending = named.queryForObject(
                "SELECT count(1) FROM tp_ai_chat_draft WHERE session_id = :sessionId"
                        + " AND object_type = :objectType AND target_id = :targetId"
                        + " AND status = :pending",
                new MapSqlParameterSource()
                        .addValue("sessionId", sessionId)
                        .addValue("objectType", DRAFT_OBJECT_REQUIREMENT_WRITE)
                        .addValue("targetId", targetId)
                        .addValue("pending", DRAFT_STATUS_PENDING),
                Integer.class);
        if (pending != null && pending > 0) {
            return true;
        }
        Integer sealed = named.queryForObject(
                "SELECT count(1) FROM tp_ai_chat_draft WHERE session_id = :sessionId"
                        + " AND object_type = :objectType AND target_id = :targetId"
                        + " AND status <> :pending AND payload_json::jsonb = CAST(:payloadJson AS jsonb)",
                new MapSqlParameterSource()
                        .addValue("sessionId", sessionId)
                        .addValue("objectType", DRAFT_OBJECT_REQUIREMENT_WRITE)
                        .addValue("targetId", targetId)
                        .addValue("pending", DRAFT_STATUS_PENDING)
                        .addValue("payloadJson", payloadJson),
                Integer.class);
        return sealed != null && sealed > 0;
    }

    /** Proposal body shaped exactly like the requirement write strategy consumes it. */
    private String payloadOf(ExtractionItem item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", item.title());
        payload.put("acceptanceCriteria", item.acceptanceCriteria());
        payload.put("chapter", item.chapter());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("update proposal payload serialization failed", e);
        }
    }

    private String summaryOf(ExtractionItem item, DedupCandidate matched) {
        String base = "Update proposal from document re-extraction for requirement \""
                + matched.title() + "\"";
        return base.length() > 480 ? base.substring(0, 480) : base;
    }
}
