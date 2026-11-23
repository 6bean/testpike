package com.sixbean.testpike.core.apiaccess.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessWriteRequest;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessWriteResult;
import com.sixbean.testpike.core.apiaccess.domain.service.ApiAccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Landing strategy for a confirmed interface pass-through change (draft kind {@code API_ACCESS_WRITE}).
 *
 * <p>The card states the intent; this class is the only place the change actually happens, and it
 * happens behind every gate again: the policy verdict (switch, family exclusions, governance), the
 * declared-parameter and declared-body whitelist, project membership of the person who confirmed,
 * and the change budget. Nothing is trusted from the proposal — the owning project is read from the
 * chat session, exactly like the other write kinds.
 *
 * <p>If the target door refuses, the business table stays untouched and the refusal is re-raised so
 * the confirmation ledger keeps the draft in a failed state with a plain reason for the human.
 */
@Service
public class ApiAccessWriteDraftStrategy implements DraftWriteStrategy {

    /** Draft kind token routed here from the registry (exec_spec draftObjectType). */
    public static final String OBJECT_TYPE = "API_ACCESS_WRITE";

    private static final Logger log = LoggerFactory.getLogger(ApiAccessWriteDraftStrategy.class);

    private final ApiAccessService apiAccessService;
    private final ChatSessionService sessionService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApiAccessWriteDraftStrategy(ApiAccessService apiAccessService,
                                       ChatSessionService sessionService,
                                       JdbcTemplate jdbcTemplate) {
        this.apiAccessService = apiAccessService;
        this.sessionService = sessionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String objectType() {
        return OBJECT_TYPE;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        long draftId = ((Number) draftRow.get("id")).longValue();
        long sessionId = ((Number) draftRow.get("session_id")).longValue();
        long projectId = sessionService.findById(sessionId).projectId();
        JsonNode body = parsePayload(String.valueOf(draftRow.get("payload_json")));
        String endpointKey = body.path("endpointKey").asText("");
        if (endpointKey.isBlank()) {
            throw new ServiceException("接口直通草案缺少条目名，无法落地（请重新让 AI 生成提案）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, Object> arguments = toMap(body.path("arguments"));

        ApiAccessWriteResult result = apiAccessService.landConfirmedChange(
                operatorId, projectId, new ApiAccessWriteRequest(endpointKey, arguments));
        if (!result.landed()) {
            log.warn("api pass-through landing refused draft={} key={} reason={}",
                    draftId, endpointKey, result.reason());
            throw new ServiceException("接口直通变更未执行：" + result.reason(), BizErrorCode.VALIDATION_ERROR);
        }
        if (result.producedId() != null) {
            jdbcTemplate.update("UPDATE tp_ai_chat_draft SET target_id = ?, updated_at = now() WHERE id = ?",
                    result.producedId(), draftId);
        }
        return null;
    }

    private JsonNode parsePayload(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new ServiceException("接口直通草案载荷不是合法 JSON，无法落地", BizErrorCode.VALIDATION_ERROR);
        }
    }

    /** Payload object → argument map (nested values stay as they are for the whitelist check). */
    private Map<String, Object> toMap(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return out;
        }
        node.fields().forEachRemaining(entry -> out.put(entry.getKey(), unwrap(entry.getValue())));
        return out;
    }

    private Object unwrap(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.isIntegralNumber() ? (Object) node.asLong() : (Object) node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isArray()) {
            java.util.List<Object> items = new java.util.ArrayList<>();
            for (JsonNode element : node) {
                items.add(unwrap(element));
            }
            return items;
        }
        if (node.isObject()) {
            return toMap(node);
        }
        return node.asText();
    }
}
