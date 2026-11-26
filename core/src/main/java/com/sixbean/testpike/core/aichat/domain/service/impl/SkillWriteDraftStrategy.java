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
import com.sixbean.testpike.core.skill.api.dto.SkillCreateInput;
import com.sixbean.testpike.core.skill.api.dto.SkillInfo;
import com.sixbean.testpike.core.skill.domain.service.SkillService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Skill-landing strategy behind the generic draft door (tool-surface
 * clearing batch stage 3b, third cleared domain): one confirmed dialog
 * proposal creates a skill row through the SAME SkillService.create chain the
 * human page uses — name/type/body validation, per-project duplicate-name
 * refusal, the v1 version-chain snapshot and audit all come free, never a
 * parallel write path (red line: no second skill save).
 * <p>The skill keeps its human approval chain after landing: the row is
 * created with submitNow left unset so it stops in draft state, exactly like
 * the page's "save without submitting" posture. Publishing stays a deliberate
 * click on /skills by a human with the approval right — confirming a dialog
 * card is never an approval.
 * <p>Attribution guard (H1): SkillService treats a null projectId as the
 * system-wide bucket, so this strategy never lets the payload choose the
 * bucket — the row is created into the SESSION's project, read from the
 * session ledger the same way the tag strategy reads it. A model-forged
 * projectId inside the payload is simply not consumed.
 * <p>Skills carry their own version chain (tp_skill_version), but a create
 * always starts at v1, so there is no separate applied version to journal:
 * the landing returns null (no version bump) and pins the new row id back
 * onto the draft ledger's target_id so the confirm receipt points at the
 * skill that exists.
 */
@Service
public class SkillWriteDraftStrategy implements DraftWriteStrategy {

    private final SkillService skillService;
    private final ChatSessionService sessionService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SkillWriteDraftStrategy(SkillService skillService,
                                   ChatSessionService sessionService,
                                   JdbcTemplate jdbcTemplate) {
        this.skillService = skillService;
        this.sessionService = sessionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String objectType() {
        return DraftObjectType.SKILL_WRITE.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        DraftLandContext ctx = DraftLandContext.open(draftRow, sessionService, objectMapper,
                "AI 技能草案内容无法解析，确认写入已拒绝（请重新让 AI 生成提案）");
        JsonNode body = ctx.body();
        long draftId = ctx.draftId(), sessionId = ctx.sessionId(), projectId = ctx.projectId();
        String name = DraftPayloadReads.text(body, "name");
        String skillBody = DraftPayloadReads.text(body, "body");
        Integer type = body.hasNonNull("type") && body.get("type").isNumber()
                ? body.get("type").asInt() : null;
        if (name == null || name.isBlank() || skillBody == null || skillBody.isBlank()
                || type == null) {
            throw new ServiceException(
                    "AI 技能草案缺少技能名 name、技能类型或正文，无法创建技能——请在「AI 对话」补全三项后重新生成提案；也可在「SKILL 管理」页新建技能",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String frontmatterJson = null;
        JsonNode frontmatter = body.get("frontmatterJson");
        if (frontmatter != null && !frontmatter.isNull()) {
            // The save door takes frontmatter as a JSON text; a model that
            // hands it as an inline object is stored by its serialized form.
            frontmatterJson = frontmatter.isTextual() ? frontmatter.asText() : frontmatter.toString();
            // The column casts to jsonb at insert; an unparsable text would
            // surface as a raw SQL error, so refuse it in plain words first.
            try {
                objectMapper.readTree(frontmatterJson);
            } catch (Exception malformed) {
                throw new ServiceException("AI 技能草案的元数据不是合法 JSON，创建未执行——请在「AI 对话」让 AI 把元数据修正为合法 JSON 后重新生成提案；也可在「SKILL 管理」页编辑元数据",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        // createdBy = the human who confirmed the card, never the model and
        // never a forged id from the payload; submitNow stays null so the row
        // lands as a draft awaiting the human approval chain.
        SkillInfo created = skillService.create(operatorId, new SkillCreateInput(
                projectId, name.trim(), type, skillBody, frontmatterJson, null));
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET target_id = ?, updated_at = now() "
                + "WHERE id = ?", created.id(), draftId);
        // A create is always v1; the ledger journals no separate applied
        // version for this kind (null = none).
        return null;
    }

}
