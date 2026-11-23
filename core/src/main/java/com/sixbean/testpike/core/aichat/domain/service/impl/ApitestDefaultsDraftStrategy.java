package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.component.DraftProjectAnchor;
import com.sixbean.testpike.core.aichat.domain.component.DraftPayloadReads;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.domain.service.ApiCollectionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Api-test collection-defaults landing strategy behind the generic draft
 * door (tool-surface clearing batch stage 5a, eighth cleared domain):
 * one confirmed dialog proposal saves a collection's default headers and/or
 * default auth block through the SAME ApiCollectionService.rename full save
 * face the collection dialog uses — the header-shape gate, the auth
 * vocabulary gate (AuthFormContract single source), the sibling-name rule
 * and the audit all come free, never a parallel write path (red line: no
 * second collection save).
 * <p>Patch semantics keep the card honest about its scope: name and tags are
 * passed null = keep stored value, so confirming a defaults card can never
 * rename a collection behind the user's back. Request-level saving stays a
 * human editor action (an editor draft's live state cannot be rebuilt from a
 * dialog payload).
 * <p>Attribution guard (H1): the owning project is never taken from the
 * payload — the collection named by collectionId is loaded and its project
 * must equal the SESSION project, otherwise the confirm is refused in plain
 * words naming the collection. A model-forged collection id therefore cannot
 * touch another project's defaults.
 * <p>Only the kind's own anchors are validated here; every business rule
 * (header array shape, auth vocabulary) stays single-sourced in
 * ApiCollectionServiceImpl — a payload it rejects surfaces in its own
 * human-readable wording, never reworded into a second spectrum.
 */
@Service
public class ApitestDefaultsDraftStrategy implements DraftWriteStrategy {

    private final ApiCollectionService apiCollectionService;
    private final DraftProjectAnchor draftProjectAnchor;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApitestDefaultsDraftStrategy(ApiCollectionService apiCollectionService,
                                        DraftProjectAnchor draftProjectAnchor,
                                        JdbcTemplate jdbcTemplate) {
        this.apiCollectionService = apiCollectionService;
        this.draftProjectAnchor = draftProjectAnchor;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String objectType() {
        return DraftObjectType.APITEST_WRITE.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        long draftId = ((Number) draftRow.get("id")).longValue();
        // H1 / AN batch piece 4a: origin-aware project read, single-sourced
        // in DraftProjectAnchor; the collection named in the payload is only
        // trusted after its ownership re-check below.
        long projectId = draftProjectAnchor.resolveForLanding(draftRow);
        JsonNode body = parseBody(String.valueOf(draftRow.get("payload_json")));
        long collectionId = body.path("collectionId").asLong(0L);
        if (collectionId <= 0) {
            throw new ServiceException(
                    "AI 接口集草案缺少合法的接口集编号，无法保存默认配置（请重新让 AI 生成提案）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String headersJson = DraftPayloadReads.text(body, "headersJson");
        String authJson = DraftPayloadReads.text(body, "authJson");
        if ((headersJson == null || headersJson.isBlank())
                && (authJson == null || authJson.isBlank())) {
            throw new ServiceException(
                    "AI 接口集草案既没有默认请求头也没有默认认证配置，没有可保存的内容（请重新让 AI 生成提案）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Existence (404 free) + cross-project refusal in plain words naming
        // the collection (same posture as the report kind's ownership guard).
        ApiCollectionInfo collection = apiCollectionService.get(collectionId);
        if (collection.projectId() != projectId) {
            throw new ServiceException(
                    "AI 接口集草案指向的接口集「" + collection.name() + "」不属于当前会话项目，已拒绝保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Full save face with patch semantics: null name/tags = keep stored.
        apiCollectionService.rename(operatorId, collectionId, null, null, headersJson, authJson);
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET target_id = ?, updated_at = now() "
                + "WHERE id = ?", collectionId, draftId);
        // An in-place update has no separate applied version for this kind
        // (null = none, mirroring the report kind's shape).
        return null;
    }

    private JsonNode parseBody(String payloadJson) {
        return DraftPayloadReads.parseOrRefuse(objectMapper, payloadJson, "AI 接口集草案内容无法解析，确认写入已拒绝（请重新让 AI 生成提案）");
    }

}
