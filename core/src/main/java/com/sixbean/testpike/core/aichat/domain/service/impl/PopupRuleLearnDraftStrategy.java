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
import com.sixbean.testpike.core.appauto.domain.service.AppautoPopupRuleService;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Popup-rule self-learning strategy behind the generic draft door (tool-
 * surface clearing batch stage 6b, twelfth cleared domain): one
 * confirmed dialog proposal learns popup patterns from ONE finished
 * execution task through the SAME {@code AppautoPopupRuleService.learn}
 * chain the approval view's learn button uses — the learning switch
 * (ai.appauto.popup-rule-learn, off = refused in plain words with zero model
 * calls and zero writes), the sensitive-frame exclusion, the structure gate
 * (fail-closed) and the pending-approval status (a learned rule row is
 * status = 1, never auto-published) all re-run inside that chain at landing
 * time, so the dialog is structurally unable to bypass one gate (red line:
 * the learn chain owns the decision, the draft card only states it).
 * <p>Attribution guard (H1): the payload only names a task id, and the
 * task's owning project is the authoritative anchor of its frame ring. This
 * strategy reads tp_exec_task FIRST and refuses a task outside the chat
 * session's project lane in plain words BEFORE any frame is collected or
 * any model call is spent — a model-forged task id therefore cannot leak
 * another project's watch frames into a rule draft.
 * <p>A learned rule starts as a pending draft, so this kind has no version
 * ledger (applied version = null): the card was emitted without a rule row
 * (target_id = 0) and the strategy rewrites it to the produced rule id, the
 * object the human then approves or rejects on /appauto/popup-rules.
 * Approving the rule into the library and rejecting it with a reason stay
 * deliberate human clicks — the card only proposes the LEARN step.
 */
@Service
public class PopupRuleLearnDraftStrategy implements DraftWriteStrategy {

    private final AppautoPopupRuleService popupRuleService;
    private final ExecTaskService execTaskService;
    private final ChatSessionService sessionService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PopupRuleLearnDraftStrategy(AppautoPopupRuleService popupRuleService,
                                       ExecTaskService execTaskService,
                                       ChatSessionService sessionService,
                                       JdbcTemplate jdbcTemplate) {
        this.popupRuleService = popupRuleService;
        this.execTaskService = execTaskService;
        this.sessionService = sessionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String objectType() {
        return DraftObjectType.POPUP_RULE_LEARN.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        DraftLandContext ctx = DraftLandContext.open(draftRow, sessionService, objectMapper,
                "AI 弹窗规则学习草案内容无法解析，确认学习已拒绝（请重新让 AI 生成提案）");
        JsonNode body = ctx.body();
        long draftId = ctx.draftId(), sessionId = ctx.sessionId(), projectId = ctx.projectId();
        long taskId = body.path("taskId").asLong(0L);
        if (taskId <= 0) {
            throw new ServiceException(
                    "AI 弹窗规则学习草案缺少执行任务编号 taskId，无法学习——请在「执行中心」打开该任务详情后从任务行发起弹窗规则学习",
                    BizErrorCode.VALIDATION_ERROR);
        }
        ExecTaskInfo task = execTaskService.get(taskId);
        if (task.projectId() != projectId) {
            throw new ServiceException(
                    "AI 弹窗规则学习草案指向的执行任务不属于当前会话项目，本次学习已拒绝；"
                            + "请前往「执行中心」选择本项目内已完成的执行任务，再在「AI 对话」让助手重新生成学习提案",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Every other legality decision (learning switch, frame availability,
        // structure gate) is re-taken by the learn chain itself; this
        // strategy only refuses a body that cannot reach it.
        long ruleId = popupRuleService.learn(taskId);
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET target_id = ?, updated_at = now() "
                + "WHERE id = ?", ruleId, draftId);
        // A pending rule draft has no version ledger for this kind — null.
        return null;
    }

}
