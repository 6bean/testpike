package com.sixbean.testpike.core.aichat.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Origin-aware project anchor of a draft card — the single source shared by
 * the landing write strategies and the confirmation door (AN batch /
 * prerequisite, plan piece 4a).
 * <p>Cards reach the ledger from two doors: a dialog answer mints one with a
 * chat session, while the linkage mint door files system-origin cards with
 * session_id NULL and a real project_id anchor column (V363 posture). Before
 * this piece the landing strategies dereferenced session_id unconditionally,
 * so a one-click accept on a linkage-minted card crashed with a raw
 * NullPointerException instead of landing or refusing in actionable words.
 * <ul>
 * <li>{@link #resolveForLanding} — a session draft keeps the untouched
 * session door (the project segment comes from the session, as before);
 * a system-origin draft resolves from the stored anchor column; a draft
 * with NEITHER origin refuses with the plain-word message.</li>
 * <li>{@link #requireStoredAnchor} — the raw anchor column read plus the
 * same refusal wording, exposed so the confirmation door governs
 * membership and the project-memory permission key on top of it without
 * carrying a second copy of the read or the message (R40).</li>
 * </ul>
 * The refusal wording is intentionally identical to the one the door has
 * always shown, so operators see one vocabulary for one defect no matter
 * which face detected it.
 */
@Component
public class DraftProjectAnchor {

    private final ChatSessionService sessionService;

    public DraftProjectAnchor(ChatSessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * The project the landing chain must run in, read from wherever this
     * card's origin actually lives: the chat session for a dialog card, the
     * stored project column for a system-origin card.
     */
    public long resolveForLanding(Map<String, Object> draftRow) {
        Object sessionId = draftRow.get("session_id");
        if (sessionId != null) {
            return sessionService.findById(((Number) sessionId).longValue()).projectId();
        }
        return requireStoredAnchor(draftRow);
    }

    /**
     * The stored project_id anchor column of a system-origin card. An
     * unanchored row governs nothing: it refuses with the actionable wording
     * (never a raw NullPointerException on session_id) so the operator can
     * act on what they read.
     */
    public long requireStoredAnchor(Map<String, Object> draftRow) {
        Object anchor = draftRow.get("project_id");
        if (anchor == null) {
            throw new ServiceException("该草案缺少所属项目，无法确认写入——请将顶栏切换到该草案所属项目后，在「AI 对话」重新发起生成提案",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return ((Number) anchor).longValue();
    }
}
