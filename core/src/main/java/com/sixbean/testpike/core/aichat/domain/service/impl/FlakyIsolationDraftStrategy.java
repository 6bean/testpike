package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Flaky-quarantine landing strategy behind the generic draft door (AO batch
 * , closed-loop operations S2): one confirmed dialog proposal
 * isolates the observed case through the SAME {@code CaseService.setFlaky}
 * chain the workbench's one-click quarantine uses — audit row, idempotent
 * reason refresh and the version bump come free, never a parallel write path.
 * <p>Shape keyed by the ledger row: {@code target_id} is the case id the
 * failure analysis observed (this kind always acts on an existing case, a
 * create-shaped card is refused), {@code base_version} the optimistic anchor
 * the analysis saw, and the payload carries only the human-readable reason
 * the card displayed.
 * <p>Attribution guard (H1): a card aimed at a case outside the session's
 * project is refused (40301) before anything moves — the ownership door of
 * the confirmation chain covers WHO may confirm, this covers WHAT the draft
 * may touch. A stale echo refuses with the plain-word version conflict so
 * the human learns the case moved since the analysis ran.
 * <p>The quarantine is deliberately one-directional through this door:
 * releasing an isolated case (and the later probe/restore scheduling) stays
 * the human face of the cases domain — confirming a card can only mark,
 * never un-mark.
 */
@Service
public class FlakyIsolationDraftStrategy implements DraftWriteStrategy {

    private final CaseService caseService;
    private final ChatSessionService sessionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FlakyIsolationDraftStrategy(CaseService caseService,
                                       ChatSessionService sessionService) {
        this.caseService = caseService;
        this.sessionService = sessionService;
    }

    @Override
    public String objectType() {
        return DraftObjectType.FLAKY_ISOLATION.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        return land(draftRow, operatorId, null);
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId, String selectedAction) {
        long sessionId = ((Number) draftRow.get("session_id")).longValue();
        long projectId = sessionService.findById(sessionId).projectId();
        Object targetRaw = draftRow.get("target_id");
        if (!(targetRaw instanceof Number caseAnchor)) {
            throw new ServiceException("隔离草案缺少目标用例，无法确认写入（请重新让 AI 分析失败原因）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        long caseId = caseAnchor.longValue();
        CaseInfo current = caseService.get(caseId);
        if (current.projectId() != projectId) {
            throw new ServiceException("该草案指向的用例不属于当前会话所在项目，确认写入已拒绝",
                    BizErrorCode.FORBIDDEN);
        }
        Object anchorRaw = draftRow.get("base_version");
        // Since the card-time anchor is re-read server-side (AM batch
        // , a mismatch here is a REAL race between showing the card
        // and confirming it — refused with the gate-baseline wording naming
        // same posture as the case-write revision-merge conflict refusal.
        if (anchorRaw instanceof Number base && current.version() != base.intValue()
                && !DraftWriteStrategy.ACTION_FORCE.equals(selectedAction)) {
            throw new ServiceException("用例已被他人修改（草案基于版本 " + base.intValue()
                    + "，当前版本 " + current.version() + "），本次隔离未写入——"
                    + "请基于最新版本重新确认，或选择「以本次为准」让本次隔离直接生效",
                    BizErrorCode.VERSION_CONFLICT);
        }
        String reason = readReason(draftRow);
        CaseInfo landed = caseService.setFlaky(operatorId, caseId, true, reason);
        return landed.version();
    }

    /** Payload reason is display text the human confirmed; anything unparsable falls back to null (the shared chain owns its default wording). */
    private String readReason(Map<String, Object> draftRow) {
        Object raw = draftRow.get("payload_json");
        if (raw == null) {
            return null;
        }
        try {
            JsonNode body = objectMapper.readTree(String.valueOf(raw));
            JsonNode reason = body == null ? null : body.get("reason");
            return reason == null || reason.isNull() ? null : reason.asText();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public java.util.Optional<com.sixbean.testpike.core.aichat.domain.component.RevisionMergeStatus>
    renewalVerdict(Map<String, Object> siblingRow, int currentVersion) {
        // A quarantine mark replays safely onto any current version: the
        // shared setFlaky chain is an idempotent refresh (repeat isolation
        // never stacks marks), so a moved case only means the sibling card
        // re-anchors — it never goes stale and the human click still decides.
        return java.util.Optional.of(
                com.sixbean.testpike.core.aichat.domain.component.RevisionMergeStatus.REBASE);
    }
}
