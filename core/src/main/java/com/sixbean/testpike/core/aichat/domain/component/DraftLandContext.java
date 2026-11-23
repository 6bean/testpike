package com.sixbean.testpike.core.aichat.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import java.util.Map;

/**
 * Attribution header shared by every draft landing strategy.
 * <p>A draft card carries its owning session; H1 pins the project segment to
 * that session, never to the payload — trusting a payload-claimed project
 * would let one card land a project asset into another project's (or the
 * system-wide) bucket. Reading the triple here once keeps every strategy's
 * refusal-free path byte-identical and the rule in one place.
 */
public record DraftLandContext(long draftId, long sessionId, long projectId, JsonNode body) {

    /** Extract draft id + session id, then resolve the owning project. */
    public static DraftLandContext of(Map<String, Object> draftRow, ChatSessionService sessionService) {
        long draftId = ((Number) draftRow.get("id")).longValue();
        long sessionId = ((Number) draftRow.get("session_id")).longValue();
        long projectId = sessionService.findById(sessionId).projectId();
        return new DraftLandContext(draftId, sessionId, projectId, null);
    }

    /**
     * Full landing preamble in one call: attribution triple plus the parsed
     * payload. An unreadable payload refuses with the caller's own sentence —
     * every domain phrases its refusal differently and that stays local.
     */
    public static DraftLandContext open(Map<String, Object> draftRow, ChatSessionService sessionService,
            ObjectMapper mapper, String refusal) {
        DraftLandContext head = of(draftRow, sessionService);
        JsonNode body = DraftPayloadReads.parseOrRefuse(mapper,
                String.valueOf(draftRow.get("payload_json")), refusal);
        return new DraftLandContext(head.draftId(), head.sessionId(), head.projectId(), body);
    }
}
