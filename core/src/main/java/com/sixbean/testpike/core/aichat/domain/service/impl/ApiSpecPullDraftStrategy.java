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
import com.sixbean.testpike.core.spec.api.dto.ApiSpecInfo;
import com.sixbean.testpike.core.spec.domain.service.ApiSpecService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Api-inventory contract-pull landing strategy behind the generic draft door
 * (tool-surface clearing batch stage 5b, ninth cleared domain): one
 * confirmed dialog proposal pulls an OpenAPI contract through the SAME
 * ApiSpecService.fetchFromUrl URL-entry chain the inventory page's pull
 * dialog uses — the SSRF whitelist, the timeout/size gates, the OpenAPI
 * format parse, the SHA256 idempotency (same content = 409), the version
 * chain and the change-analysis hook all come free from that single source,
 * never a parallel registration path (red line: no second spec writer).
 * <p>Attribution guard (H1): the owning project is never taken from the
 * payload — it is read from the chat session, exactly like the report kind.
 * A model-forged url therefore can only ever append onto the session's own
 * project version chain, and the SSRF whitelist inside the fetcher stays the
 * one place that decides which hosts may be dialed at all.
 * <p>A pull is always an append: the new spec row id is pinned back onto the
 * ledger as target_id and its spec_version is the applied version, so the
 * card reports which chain version it produced.
 * <p>Only the kind's own anchors are validated here; every business rule
 * (blank name, oversize body, non-OpenAPI content, duplicate content) stays
 * single-sourced in ApiSpecServiceImpl — a refusal there surfaces in its own
 * human-readable wording, never reworded into a second spectrum.
 */
@Service
public class ApiSpecPullDraftStrategy implements DraftWriteStrategy {

    private final ApiSpecService apiSpecService;
    private final ChatSessionService sessionService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApiSpecPullDraftStrategy(ApiSpecService apiSpecService,
                                    ChatSessionService sessionService,
                                    JdbcTemplate jdbcTemplate) {
        this.apiSpecService = apiSpecService;
        this.sessionService = sessionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String objectType() {
        return DraftObjectType.APISPEC_WRITE.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        DraftLandContext ctx = DraftLandContext.open(draftRow, sessionService, objectMapper,
                "AI 契约拉取草案内容无法解析，确认写入已拒绝（请重新让 AI 生成提案）");
        JsonNode body = ctx.body();
        long draftId = ctx.draftId(), sessionId = ctx.sessionId(), projectId = ctx.projectId();
        String name = body.path("name").asText("").trim();
        String url = body.path("url").asText("").trim();
        if (name.isEmpty() || url.isEmpty()) {
            throw new ServiceException(
                    "AI 契约拉取草案缺少契约名称 name 或文档地址 url，无法入库——请在「AI 对话」写明契约名称与 OpenAPI 文档地址后重新生成提案；也可在「接口清单」页发起契约导入",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // The fetch chain re-checks everything else itself (whitelist, size,
        // format, idempotency); this strategy only refuses a body that
        // cannot even reach it.
        ApiSpecInfo created = apiSpecService.fetchFromUrl(operatorId, projectId, name, url);
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET target_id = ?, updated_at = now() "
                + "WHERE id = ?", created.id(), draftId);
        // A pull appends one version row: the produced spec_version is the
        // applied version the confirmation reports back.
        return created.specVersion();
    }

}
