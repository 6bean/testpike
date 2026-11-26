package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.component.DraftPayloadReads;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateItem;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateSaveInput;
import com.sixbean.testpike.core.prompt.domain.service.PromptTemplateService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Prompt-template landing strategy behind the generic draft door (tool-surface
 * clearing batch stage 3a, second cleared domain): one confirmed dialog
 * proposal appends a new template version through the SAME
 * PromptTemplateService.save chain the human page uses — required-field
 * refusal, family version bump and the grey-release default status all come
 * free, never a parallel write path (red line: no second template save).
 * <p>The template family is append-only ("edit = new version row"), so this
 * strategy knows exactly one shape: a create. The new row id is pinned back
 * onto the draft ledger's target_id when the write lands, mirroring the tag
 * create posture, so the confirm receipt points at the version that exists.
 * <p>Attribution: prompt templates carry no project column — the family is a
 * platform-wide asset and the human entry is permission-gated on the admin
 * bridge (perm:feedback:model-update), so the per-project requireSameProject
 * guard other strategies use has nothing here to compare against. The
 * confirm door itself still decides WHO may land (ticket + horizontal-access
 * checks upstream); this strategy only refuses payloads the save door would
 * reject anyway, surfacing PromptTemplateService's own human-readable errors
 * unchanged.
 */
@Service
public class PromptTplWriteDraftStrategy implements DraftWriteStrategy {

    private final PromptTemplateService promptTemplateService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PromptTplWriteDraftStrategy(PromptTemplateService promptTemplateService,
                                       JdbcTemplate jdbcTemplate) {
        this.promptTemplateService = promptTemplateService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String objectType() {
        return DraftObjectType.PROMPT_TPL_WRITE.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        long draftId = ((Number) draftRow.get("id")).longValue();
        JsonNode body = parseBody(String.valueOf(draftRow.get("payload_json")));
        String name = DraftPayloadReads.text(body, "name");
        String domain = DraftPayloadReads.text(body, "domain");
        String content = DraftPayloadReads.text(body, "content");
        if (name == null || name.isBlank() || domain == null || domain.isBlank()
                || content == null || content.isBlank()) {
            throw new ServiceException(
                    "AI 提示词模板草案缺少模板名 name、业务域 domain 或正文 content，无法保存新版本——请在「AI 对话」补全三项后重新生成提案；也可在「Prompt 模板管理」页编辑保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // createdBy = the human who confirmed the card, never the model and
        // never a forged id from the payload: the operator id comes from the
        // confirmation chain the same way every other strategy receives it.
        PromptTemplateItem saved = promptTemplateService.save(
                new PromptTemplateSaveInput(name.trim(), domain.trim(), content, operatorId));
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET target_id = ?, updated_at = now() "
                + "WHERE id = ?", saved.id(), draftId);
        // The version number lives on the template row itself; the ledger has
        // no separate applied-version journal for this kind (null = none).
        return null;
    }

    private JsonNode parseBody(String payloadJson) {
        return DraftPayloadReads.parseOrRefuse(objectMapper, payloadJson, "AI 提示词模板草案内容无法解析，确认写入已拒绝（请重新让 AI 生成提案）");
    }

}
