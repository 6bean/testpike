package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Distilled project-memory landing strategy behind the generic draft door
 * (AP batch , the confirmation gate re-opened by the user's
 * re-ruling).
 * <p>The system-origin twin of the dialog kinds: the ledger row carries no
 * chat session, so the owning project comes from the row's own project
 * anchor (already guarded by the door's system branch — login, anchor
 * existence, membership, memory permission key), never from the payload.
 * <p>Landing is a promotion, not a write: distillation inserted the entry
 * awaiting confirmation ({@code tp_ai_ctx_memory.inject_status = 2}, never
 * injected), and the confirmed human flips exactly that row to active (1),
 * which is when — and only when — it becomes assembly-injectable. The
 * conditional UPDATE doubles as the optimistic anchor: a row already
 * promoted, disabled or removed by another hand never matches, and the
 * refusal keeps the draft pending instead of half-applying (same posture as
 * the version-anchored kinds). One injection chain, no second write path.
 */
@Service
public class CtxMemoryDraftStrategy implements DraftWriteStrategy {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NamedParameterJdbcTemplate named;

    public CtxMemoryDraftStrategy(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    @Override
    public String objectType() {
        return DraftObjectType.CTX_MEMORY.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        Object anchor = draftRow.get("project_id");
        if (anchor == null) {
            // Defence in depth behind the door's anchor guard: an unanchored
            // row must never reach a project-scoped write.
            throw new ServiceException("该草案缺少所属项目（草案行 project_id 为空），无法确认写入"
                    + "——请在顶栏「选择项目」确认当前项目后，回到右侧对话坞重新让 AI 生成提案，本卡无需再确认",
                    BizErrorCode.VALIDATION_ERROR);
        }
        long projectId = ((Number) anchor).longValue();
        String content = payloadContent(draftRow.get("payload_json"));
        int promoted = named.update("""
                UPDATE tp_ai_ctx_memory
                SET inject_status = 1, updated_at = now()
                WHERE project_id = :projectId AND content = :content AND inject_status = 2
                """, new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("content", content));
        if (promoted == 0) {
            throw new ServiceException("该记忆条目已不存在或状态已变化（不再是待采纳态），本次确认未写入"
                    + "——可在左侧菜单「SKILL 管理」的「项目记忆」页签查看该条目当前状态，"
                    + "或回到右侧对话坞重新让 AI 归纳生成新草案",
                    BizErrorCode.NOT_FOUND);
        }
        // tp_ai_ctx_memory has no version ledger (same null shape as the
        // in-place kinds); the promoted row is the object the human confirmed.
        return null;
    }

    /** The distilled text the card was emitted with (payload contract {content}). */
    private String payloadContent(Object payloadJson) {
        String raw = payloadJson == null ? "" : String.valueOf(payloadJson);
        try {
            JsonNode body = MAPPER.readTree(raw);
            String content = body.path("content").asText("");
            if (content.isBlank()) {
                throw new ServiceException("该记忆草案缺少条目正文（草案载荷 content 为空），无法确认写入"
                        + "——请让 AI 重新归纳生成带正文的记忆提案（右侧对话坞「草案卡」处发起），本卡无需再确认",
                        BizErrorCode.VALIDATION_ERROR);
            }
            return content;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("该记忆草案内容格式不合法（草案载荷不是合法 JSON 或缺少 content 字段），无法确认写入"
                    + "——本卡请勿再确认，请在顶栏「选择项目」确认项目后回到右侧对话坞重新让 AI 生成提案",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }
}
