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
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.domain.service.MockSpaceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Mock-space create strategy behind the generic draft door (tool-surface
 * clearing batch stage 7, thirteenth cleared domain): one confirmed
 * dialog proposal opens a mock space through the SAME
 * {@code MockSpaceService.create} chain the space page's dialog uses — the
 * structural validator (name length, routing-key shape, mode/auth/miss-policy
 * one-of sets, rate/quota/chaos bounds), the project existence check, the
 * production-environment ban and the site-wide routing-key uniqueness
 * conflict all re-run inside that transaction at landing time, so the dialog
 * is structurally unable to bypass one gate (red line: the create chain owns
 * the decision, the draft card only states it).
 * <p>Attribution guard (H1): the owning project is read from the chat
 * session, never from the payload — {@code MockSpaceInput} carries no project
 * field by design, so a model-forged call cannot open a space in another
 * project's lane. The optional environment anchor is only trusted when it
 * belongs to the session project; the create chain itself re-checks this
 * (a foreign or missing env is refused with the same plain-word 403), so the
 * payload can never steer a binding across projects.
 * <p>A created space is always a DRAFT row (status = 0): confirming the card
 * only produces the draft space, while publishing it (the transition that
 * opens the traffic door under /mocks/) stays a deliberate human click on
 * /mock/spaces. The draft row has no version ledger for this kind (applied
 * version = null); the card is emitted without a space row (target_id = 0)
 * and this strategy rewrites it to the produced space id, the object the
 * human then publishes, edits or deletes.
 */
@Service
public class MockSpaceCreateDraftStrategy implements DraftWriteStrategy {

    private final MockSpaceService mockSpaceService;
    private final ChatSessionService sessionService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MockSpaceCreateDraftStrategy(MockSpaceService mockSpaceService,
                                        ChatSessionService sessionService,
                                        JdbcTemplate jdbcTemplate) {
        this.mockSpaceService = mockSpaceService;
        this.sessionService = sessionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String objectType() {
        return DraftObjectType.MOCK_SPACE_WRITE.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        DraftLandContext ctx = DraftLandContext.open(draftRow, sessionService, objectMapper,
                "AI Mock 空间草案内容无法解析，确认创建已拒绝（请重新让 AI 生成提案）");
        JsonNode body = ctx.body();
        long draftId = ctx.draftId(), sessionId = ctx.sessionId(), projectId = ctx.projectId();
        String name = body.path("name").asText("").trim();
        String spaceKey = body.path("spaceKey").asText("").trim();
        if (name.isEmpty() || spaceKey.isEmpty()) {
            throw new ServiceException(
                    "AI Mock 空间草案缺少空间名称 name 或路由标识 spaceKey，无法创建——请在「AI 对话」补全两项后重新生成提案；也可在「仿真空间」页新建空间",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Every other legality decision (key shape, mode/auth/policy one-of,
        // production-env ban, cross-project env, uniqueness conflict) is
        // re-taken by the create chain itself; this strategy only refuses a
        // body that cannot reach it.
        long envId = body.path("envId").asLong(0L);
        long spaceId = mockSpaceService.create(projectId, new MockSpaceInput(
                name,
                spaceKey,
                DraftPayloadReads.textOrNull(body, "alias"),
                envId > 0 ? envId : null,
                DraftPayloadReads.textOrNull(body, "basePathMode"),
                DraftPayloadReads.textOrNull(body, "authMode"),
                body.hasNonNull("rateLimitRps") ? body.path("rateLimitRps").asInt(0) : null,
                body.hasNonNull("quotaDaily") ? body.path("quotaDaily").asLong(0) : null,
                body.hasNonNull("priority") ? body.path("priority").asInt(0) : null,
                body.hasNonNull("chaosPercent") ? body.path("chaosPercent").asInt(0) : null,
                DraftPayloadReads.textOrNull(body, "missPolicy")));
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET target_id = ?, updated_at = now() "
                + "WHERE id = ?", spaceId, draftId);
        // A draft space row has no version ledger for this kind — null.
        return null;
    }

    /** Blank or missing optional text reads as "not stated" (the chain defaults it). */
}
