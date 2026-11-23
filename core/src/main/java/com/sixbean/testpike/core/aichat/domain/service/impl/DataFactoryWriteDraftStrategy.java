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
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryCreateInput;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryUpdateInput;
import com.sixbean.testpike.core.datafactory.domain.service.DataFactoryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Data-factory script create/edit strategy behind the generic draft door
 * (tool-surface clearing batch stage 11, seventeenth cleared domain).
 * One confirmed dialog proposal files or edits a data-factory script through
 * the SAME {@code DataFactoryService.create/update} chain the data-factory
 * page's editor uses — the type one-of (API/SQL/script), the name and content
 * non-blank mandates, the env-belongs-to-project check and the pool-key
 * grammar plus per project+env uniqueness gate all re-run at landing time,
 * so the dialog is structurally unable to bypass one gate (red line: the
 * save chain owns the decision, the draft card only states it).
 * <p>Attribution guard (H1): the owning project is read from the chat
 * session, never from the payload — {@code DataFactoryCreateInput} carries a
 * project field the model COULD steer, so this strategy drops the payload
 * value and files the row in the session's project (same posture as the
 * defect kind). An edit re-reads the target row first and refuses a foreign
 * project's script before the update chain is even dialed.
 * <p>Confirming the card only saves the script DEFINITION: running it — the
 * part that dials the real API/SQL/python engine and writes the encrypted
 * data pool — stays a deliberate human click on /data-factories, and the
 * enabled/disabled switch of an edit only travels when the payload states
 * one. tp_data_factory has no version ledger (rows are updated in place,
 * same null-applied-version shape as the api-test and report kinds), so the
 * landing returns null and the next echo anchors on the row id alone.
 * <p>One bean serves both verbs (target_id = 0 means create, > 0 means edit)
 * exactly like the mock-stub strategy: the two built-in tools
 * (datafactory.create / datafactory.update) share the DATA_FACTORY_WRITE
 * kind and the card anchors already pin which side of the door lands.
 */
@Service
public class DataFactoryWriteDraftStrategy implements DraftWriteStrategy {

    private final DataFactoryService dataFactoryService;
    private final ChatSessionService sessionService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DataFactoryWriteDraftStrategy(DataFactoryService dataFactoryService,
                                         ChatSessionService sessionService,
                                         JdbcTemplate jdbcTemplate) {
        this.dataFactoryService = dataFactoryService;
        this.sessionService = sessionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String objectType() {
        return DraftObjectType.DATA_FACTORY_WRITE.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        DraftLandContext ctx = DraftLandContext.open(draftRow, sessionService, objectMapper,
                "AI 造数脚本草案内容无法解析，确认写入已拒绝（请重新让 AI 生成提案）");
        JsonNode body = ctx.body();
        long draftId = ctx.draftId(), sessionId = ctx.sessionId(), projectId = ctx.projectId();
        Object targetRaw = draftRow.get("target_id");
        long targetId = targetRaw == null ? 0L : ((Number) targetRaw).longValue();
        return targetId > 0
                ? landEdit(projectId, body, targetId, operatorId)
                : landCreate(draftId, projectId, body, operatorId);
    }

    /** Create: the session project owns the row; the produced id pins back onto the ledger. */
    private Integer landCreate(long draftId, long projectId, JsonNode body, long operatorId) {
        String name = DraftPayloadReads.text(body, "name");
        String content = body.path("content").asText("").trim();
        if (name == null) {
            throw new ServiceException(
                    "AI 造数脚本草案缺少脚本名称，无法保存（请重新让 AI 生成提案）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (content.isEmpty()) {
            throw new ServiceException(
                    "AI 造数脚本草案缺少脚本内容，无法保存（请重新让 AI 生成提案）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // The type/env/pool-key legality spectrum is NOT re-decided here —
        // the create chain owns that; this door only swaps the project the
        // payload named for the session's project.
        var created = dataFactoryService.create(operatorId, new DataFactoryCreateInput(
                projectId,
                body.path("envId").asLong(0L),
                body.path("type").asInt(0),
                name,
                content,
                DraftPayloadReads.text(body, "paramsJson")));
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET target_id = ?, updated_at = now() "
                + "WHERE id = ?", created.id(), draftId);
        // tp_data_factory has no version ledger: applied version = null,
        // same as the api-test and report kinds.
        return null;
    }

    /** Edit: the named row must live in the session project, then the shared save chain runs. */
    private Integer landEdit(long projectId, JsonNode body, long factoryId, long operatorId) {
        var current = dataFactoryService.get(operatorId, factoryId);
        if (current.projectId() != projectId) {
            throw new ServiceException("该草案指向的造数脚本不属于当前会话所在项目，确认写入已拒绝",
                    BizErrorCode.FORBIDDEN);
        }
        Integer status = body.hasNonNull("status") && body.get("status").isNumber()
                ? body.get("status").asInt() : null;
        if (status != null && status != 1 && status != 2) {
            throw new ServiceException(
                    "AI 造数脚本草案的启停状态不合法（1=启用 2=停用），本次编辑未写入（请重新让 AI 生成提案）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        dataFactoryService.update(operatorId, factoryId, new DataFactoryUpdateInput(
                DraftPayloadReads.text(body, "name"),
                body.path("content").asText("").trim().isEmpty() ? null : body.path("content").asText("").trim(),
                status,
                DraftPayloadReads.text(body, "paramsJson")));
        return null;
    }

    /** Blank or missing optional text reads as "not stated" (the chain keeps the old value). */
}
