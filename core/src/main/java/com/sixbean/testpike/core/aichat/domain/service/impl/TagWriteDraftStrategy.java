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
import com.sixbean.testpike.core.tags.api.dto.TagCreateInput;
import com.sixbean.testpike.core.tags.api.dto.TagInfo;
import com.sixbean.testpike.core.tags.api.dto.TagMergeInput;
import com.sixbean.testpike.core.tags.api.dto.TagRenameInput;
import com.sixbean.testpike.core.tags.domain.service.TagService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Tag-write landing strategy behind the generic draft door (tool-surface
 * clearing batch stage 2, first cleared domain): one confirmed dialog
 * proposal creates, renames or merges a tag-dictionary row through the SAME
 * TagService chain the human UI uses — same-project duplicate refusal,
 * reference migration on merge and audit all come free, never a parallel
 * write path (red line: no second tag save).
 * <p>Three shapes keyed off the payload itself:
 * <ul>
 * <li>name, no tagId/fromTagId = create into the session's project (the
 * ledger row's target_id is pinned back with the new tag id);</li>
 * <li>tagId + newName = rename;</li>
 * <li>fromTagId + toTagId = merge (relations move, from-tag deleted).</li>
 * </ul>
 * <p>Attribution guard (H1): a rename/merge aimed at a tag outside the
 * session's project is refused (40301) before anything moves — same rule as
 * {@link CaseWriteDraftStrategy}: the door covers WHO may confirm, this
 * covers WHAT the draft may touch. The tag dictionary has no per-row version
 * ledger, so the landing returns null (no applied version to journal) and
 * business refusals (duplicate name, dangling reference) surface as
 * TagService's own human-readable errors, never reworded here.
 */
@Service
public class TagWriteDraftStrategy implements DraftWriteStrategy {

    private final TagService tagService;
    private final ChatSessionService sessionService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TagWriteDraftStrategy(TagService tagService,
                                 ChatSessionService sessionService,
                                 JdbcTemplate jdbcTemplate) {
        this.tagService = tagService;
        this.sessionService = sessionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String objectType() {
        return DraftObjectType.TAG_WRITE.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        DraftLandContext ctx = DraftLandContext.open(draftRow, sessionService, objectMapper,
                "AI 标签草案内容无法解析，确认写入已拒绝（请重新让 AI 生成提案）");
        JsonNode body = ctx.body();
        long draftId = ctx.draftId(), sessionId = ctx.sessionId(), projectId = ctx.projectId();
        long fromTagId = body.path("fromTagId").asLong(0L);
        if (fromTagId > 0) {
            return landMerge(draftRow, projectId, body, fromTagId, operatorId);
        }
        long tagId = body.path("tagId").asLong(0L);
        if (tagId > 0) {
            return landRename(projectId, body, tagId, operatorId);
        }
        return landCreate(draftId, projectId, body, operatorId);
    }

    private Integer landCreate(long draftId, long projectId, JsonNode body, long operatorId) {
        String name = DraftPayloadReads.text(body, "name");
        if (name == null || name.isBlank()) {
            throw new ServiceException("标签草案缺少「标签名」（name 为空），无法创建标签——"
                    + "请在对话里补充名称（示例「回归冒烟」），系统会重新出草案卡（入口：右侧对话坞 → 草案卡）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Integer type = body.hasNonNull("type") && body.get("type").isNumber()
                ? body.get("type").asInt() : null;
        TagInfo created = tagService.create(operatorId,
                new TagCreateInput(projectId, name.trim(), type));
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET target_id = ?, updated_at = now() "
                + "WHERE id = ?", created.id(), draftId);
        return null;
    }

    private Integer landRename(long projectId, JsonNode body, long tagId, long operatorId) {
        requireSameProject(tagId, projectId);
        String newName = DraftPayloadReads.text(body, "newName");
        if (newName == null || newName.isBlank()) {
            throw new ServiceException("标签草案缺少「新名称」（newName 为空），改名未执行——"
                    + "请在对话里给出目标名称（示例「性能基线」），系统会重新出草案卡（入口：右侧对话坞 → 草案卡）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        tagService.rename(operatorId, tagId, new TagRenameInput(newName.trim()));
        return null;
    }

    private Integer landMerge(Map<String, Object> draftRow, long projectId, JsonNode body,
                              long fromTagId, long operatorId) {
        long toTagId = body.path("toTagId").asLong(0L);
        // The ledger anchors the from-tag (rename and merge share target_id);
        // a payload that moved away from its anchor is a tampered snapshot.
        Object rowTarget = draftRow.get("target_id");
        long anchored = rowTarget == null ? 0L : ((Number) rowTarget).longValue();
        if (anchored > 0 && anchored != fromTagId) {
            throw new ServiceException("草案的「源标签」（编号 " + fromTagId + "）与登记的「目标标签」（编号 "
                    + anchored + "）不是同一个，合并未执行——本次提案已失效，"
                    + "请在右侧对话坞重新要求合并标签并立即接受（入口：对话坞 → 草案卡）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (toTagId <= 0) {
            throw new ServiceException("标签草案缺少「合并目标」（toTagId 为空），合并未执行——"
                    + "请在对话里指明并入哪个标签（示例「把标签A并入标签B」），系统会重新出草案卡（入口：右侧对话坞 → 草案卡）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        requireSameProject(fromTagId, projectId);
        requireSameProject(toTagId, projectId);
        tagService.merge(operatorId, new TagMergeInput(fromTagId, toTagId));
        return null;
    }

    /** H1 attribution: both tags of the proposal must live in the session project. */
    private void requireSameProject(long tagId, long projectId) {
        TagInfo tag = tagService.get(tagId);
        if (tag.projectId() != projectId) {
            throw new ServiceException("草案指向的标签「" + tagId + "」不属于当前会话所在项目（项目「"
                    + projectId + "」），确认写入已拒绝——"
                    + "请通过顶栏「选择项目」切到该标签所在项目，或在当前项目让 AI 重新创建标签",
                    BizErrorCode.FORBIDDEN);
        }
    }

}
