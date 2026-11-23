package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.ReviewResult;
import com.sixbean.testpike.core.cases.domain.component.ReviewAction;
import com.sixbean.testpike.core.aichat.domain.component.DraftPayloadReads;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Review-decision landing strategy behind the generic draft door (tool-surface
 * clearing batch stage 3c, fourth cleared domain): one confirmed dialog
 * proposal moves a case through the SAME CaseService.review chain the review
 * workbench uses — state-machine legality, remark/feedback-type requirements,
 * the optimistic version bump, the review log, the version snapshot and audit
 * all come free, never a parallel review path (red line: no second state
 * transition implementation).
 * <p>Attribution guard (H1): a decision aimed at a case outside the session's
 * project is refused (40301) before anything moves — the ownership door of
 * the confirmation chain covers WHO may confirm, this covers WHAT the draft
 * may touch (same rule as {@link CaseWriteDraftStrategy}).
 * <p>Optimistic anchor: the card pinned base_version = the case version the
 * proposal was cut against; a stale card is refused in the same human-readable
 * wording the workbench uses, so confirming never silently overwrites a newer
 * decision made by another reviewer.
 * <p>Only the kind's own anchors are validated here; every business rule
 * (legal transitions per state, remark-required config, feedback-type codes)
 * stays single-sourced in CaseServiceImpl — a payload CaseService rejects
 * surfaces as CaseService's own error, never reworded into a second spectrum.
 */
@Service
public class CaseReviewDraftStrategy implements DraftWriteStrategy {

    private final CaseService caseService;
    private final ChatSessionService sessionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CaseReviewDraftStrategy(CaseService caseService, ChatSessionService sessionService) {
        this.caseService = caseService;
        this.sessionService = sessionService;
    }

    @Override
    public String objectType() {
        return DraftObjectType.CASE_REVIEW.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        long sessionId = ((Number) draftRow.get("session_id")).longValue();
        ChatSessionInfo session = sessionService.findById(sessionId);
        JsonNode body = parseBody(String.valueOf(draftRow.get("payload_json")));
        Object targetRaw = draftRow.get("target_id");
        if (targetRaw == null) {
            // A review decision without its case anchor cannot exist: the
            // emitter shape refuses it, so a ledger row missing the anchor is
            // a half-written snapshot, never a create attempt.
            throw new ServiceException("AI 评审草案缺少用例编号，确认写入已拒绝（请重新让 AI 生成提案）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        long caseId = ((Number) targetRaw).longValue();
        int expectedVersion = ((Number) draftRow.get("base_version")).intValue();
        CaseInfo current = caseService.get(caseId);
        if (current.projectId() != session.projectId()) {
            throw new ServiceException("该草案指向的用例不属于当前会话所在项目，确认写入已拒绝",
                    BizErrorCode.FORBIDDEN);
        }
        String actionCode = DraftPayloadReads.text(body, "action");
        if (actionCode == null || actionCode.isBlank()) {
            throw new ServiceException("AI 评审草案缺少评审动作（pass/need_change/reject），确认写入已拒绝",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // The shared review chain owns every business rule from here on:
        // illegal transition 40902, missing feedback types 40001, stale
        // version conflict — all come out of CaseService in its own words.
        ReviewResult result = caseService.review(operatorId, caseId, ReviewAction.from(actionCode),
                DraftPayloadReads.text(body, "remark"), expectedVersion, readFeedbackTypes(body));
        return result.version();
    }

    /** feedbackTypes array of codes (null/absent = none; CaseService validates codes). */
    private static List<String> readFeedbackTypes(JsonNode body) {
        JsonNode node = body.get("feedbackTypes");
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> codes = new ArrayList<>();
        node.forEach(item -> {
            if (item.isTextual() && !item.asText().isBlank()) {
                codes.add(item.asText());
            }
        });
        return codes;
    }

    private JsonNode parseBody(String payloadJson) {
        return DraftPayloadReads.parseOrRefuse(objectMapper, payloadJson, "AI 评审草案内容无法解析，确认写入已拒绝（请重新让 AI 生成提案）");
    }

}
