package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.component.DraftPayloadReads;
import com.sixbean.testpike.core.aichat.domain.component.DraftLandContext;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.explore.api.dto.ExploreCreateInput;
import com.sixbean.testpike.core.explore.domain.service.ExploreService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Exploration-session start strategy behind the generic draft door
 * (tool-surface clearing batch stage 12, eighteenth and last debt
 * domain cleared). One confirmed dialog proposal files an exploration
 * session through the SAME {@code ExploreService.create} chain the explore
 * page's dialog runs — the budget fuse (a project out of AI budget is
 * refused before anything is filed), the goal non-blank and 2000-char
 * mandates, the web-form SSRF floor (file://, internal and metadata
 * addresses refused) and the mobile-form env whitelist gate all re-run at
 * landing time, so the dialog is structurally unable to bypass one gate
 * (red line: the create chain owns the decision, the draft card only
 * states it).
 * <p>Attribution guard (H1): the owning project is read from the chat
 * session, never from the payload. The tool schema may carry a project
 * field the model COULD steer, so this strategy drops it and files the
 * session in the chat session's project (same posture as the defect and
 * data-factory kinds).
 * <p>Unlike the definition-saving kinds, confirming this card STARTS work:
 * the explore kernel picks the session up (status=1 running) and spends
 * real tokens against the project budget — the card says so up front and
 * the exploration stays cancellable on /explore (creator/ADMIN, shared
 * cancel chain). tp_explore_session has no version ledger (rows move
 * through their own status machine, not through drafts), so the landing
 * returns the null applied-version shape and pins the produced session id
 * back onto the ledger as the target anchor.
 */
@Service
public class ExploreCreateDraftStrategy implements DraftWriteStrategy {

    private final ExploreService exploreService;
    private final ChatSessionService sessionService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ExploreCreateDraftStrategy(ExploreService exploreService,
                                      ChatSessionService sessionService,
                                      JdbcTemplate jdbcTemplate) {
        this.exploreService = exploreService;
        this.sessionService = sessionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String objectType() {
        return DraftObjectType.EXPLORE_CREATE.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        DraftLandContext ctx = DraftLandContext.open(draftRow, sessionService, objectMapper,
                "AI 探索草案内容无法解析，确认写入已拒绝（请重新让 AI 生成提案）");
        JsonNode body = ctx.body();
        long draftId = ctx.draftId(), sessionId = ctx.sessionId(), projectId = ctx.projectId();
        String goal = body.path("goalText").asText("").trim();
        String startUrl = body.path("startUrl").asText("").trim();
        if (goal.isEmpty() || startUrl.isEmpty()) {
            throw new ServiceException(
                    "AI 探索草案缺少探索目标或起始地址，无法发起（请重新让 AI 生成提案）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // The budget fuse, the goal length cap, the SSRF floor and the
        // mobile env whitelist are NOT re-decided here — the create chain
        // owns those; this door only swaps the project the payload named
        // for the session's project.
        Long envId = body.hasNonNull("envId") && body.get("envId").isNumber()
                && body.get("envId").asLong() > 0 ? body.get("envId").asLong() : null;
        String credRef = body.path("credRef").asText("").trim();
        String loginStateName = body.path("loginStateName").asText("").trim();
        long created = exploreService.create(operatorId, projectId,
                new ExploreCreateInput(goal, startUrl, envId,
                        credRef.isEmpty() ? null : credRef,
                        loginStateName.isEmpty() ? null : loginStateName));
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET target_id = ?, updated_at = now() "
                + "WHERE id = ?", created, draftId);
        // tp_explore_session has no version ledger: applied version = null,
        // same as the data-factory and api-test kinds.
        return null;
    }

}
