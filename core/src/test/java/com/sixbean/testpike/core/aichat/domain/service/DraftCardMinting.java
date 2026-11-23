package com.sixbean.testpike.core.aichat.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import com.sixbean.testpike.core.aichat.domain.service.ChatFrameService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared test harness that mints CASE_WRITE draft cards through the REAL
 * frame ledger (ChatFrameService write point), so every suite cuts proposals
 * with the production issue-order seq instead of hand-writing rows.
 * <p>Single source for the meta assembly that the case-write flow suite and
 * the queue-renewal suite both need — kept in one place per the "no duplicated
 * logic" rule: suites delegate here instead of copying the frame payload.
 */
@Component
public class DraftCardMinting {

    @Autowired
    private ChatFrameService frameService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Open an assistant answer the emitter way, so several cards group under it . */
    public long openAssistantMessage(long sessionId) {
        return frameService.openMessage(sessionId, "assistant", null);
    }

    /**
 * Append a DRAFT frame carrying a CASE_WRITE proposal for {@code caseId}
 * anchored at {@code baseVersion} onto the GIVEN answer message, and
 * return the draft row id the ledger pinned into that frame's own meta.
 * Since piece 1 one message may carry several cards, so the read-
 * back must never use the old {@code WHERE message_id = ?} single-key
 * shape (NonUniqueResultException on grouped messages) — the frame pin
 * is exact per proposal.
 * {@code caseId = 0} with baseVersion 0 is the create-kind anchor shape.
 */
    public long caseWriteDraftInMessage(long sessionId, long messageId, long caseId,
                                        int baseVersion, String payloadJson) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("objectType", "CASE_WRITE");
        meta.put("caseId", caseId);
        meta.put("baseVersion", baseVersion);
        meta.put("model", "test-model");
        meta.put("actionJson", payloadJson);
        long seq;
        try {
            seq = frameService.appendFrame(sessionId, messageId, ChatFrameType.DRAFT,
                    "case write proposal", objectMapper.writeValueAsString(meta));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Long pinned = jdbcTemplate.queryForObject(
                "SELECT (meta_json->>'draftId')::bigint FROM tp_ai_chat_frame"
                        + " WHERE session_id = ? AND seq = ?",
                Long.class, sessionId, seq);
        if (pinned == null) {
            throw new IllegalStateException("frame ledger did not pin a draftId for seq " + seq);
        }
        return pinned;
    }

    /**
     * Mint one CASE_WRITE card on its own fresh answer message (the classic
     * one-card-per-message shape most suites model). Delegates so the meta
     * assembly stays single-sourced.
     */
    public long caseWriteDraft(long sessionId, long caseId, int baseVersion, String payloadJson) {
        return caseWriteDraftInMessage(sessionId, openAssistantMessage(sessionId),
                caseId, baseVersion, payloadJson);
    }
}
