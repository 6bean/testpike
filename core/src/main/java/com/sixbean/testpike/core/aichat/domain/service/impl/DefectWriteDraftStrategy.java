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
import com.sixbean.testpike.core.defects.api.dto.DefectCreateInput;
import com.sixbean.testpike.core.defects.api.dto.DefectTransitionInput;
import com.sixbean.testpike.core.defects.api.dto.DefectUpdateInput;
import com.sixbean.testpike.core.defects.domain.service.DefectLibraryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Defect create/edit/transition strategy behind the generic draft door
 * (tool-surface clearing batch stage 10, sixteenth cleared domain): one
 * confirmed dialog proposal moves the defect library through the SAME
 * {@code DefectLibraryService.create/update/transition} chain the defect
 * page's dialogs use — the title ceiling, the severity one-of, the
 * same-project checks on the linked case and plan, the fixer-membership
 * refusal, the closed-state edit ban, the six-state transition table, the
 * reason mandate on closing/reopening and the optimistic version conflict
 * all re-run at landing time, so the dialog is structurally unable to bypass
 * one gate (red line: the write chain owns the decision, the draft card only
 * states it).
 * <p>Attribution guard (H1): unlike every create kind cleared so far,
 * {@code DefectCreateInput} carries a project field the model COULD steer —
 * this strategy ignores the payload value entirely and injects the session
 * project, so a forged call cannot file a defect in another project's lane.
 * Edit and transition read the target row first and compare its project
 * against the session BEFORE calling the chain; the chain would refuse a
 * foreign row only incidentally (via the linked-entity checks), so this door
 * adds the explicit cross-project refusal.
 * <p>The three shapes are told apart by payload anchors (same posture as the
 * tag family, one kind three tools): a body naming {@code toStatus} is a
 * transition, an anchored {@code target_id} without it is an edit, and no
 * anchor at all is a create. Create pins the produced defect id back onto
 * the ledger row; edit and transition return the applied row version so the
 * human's next echo anchors correctly.
 */
@Service
public class DefectWriteDraftStrategy implements DraftWriteStrategy {

    private final DefectLibraryService defectLibraryService;
    private final ChatSessionService sessionService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DefectWriteDraftStrategy(DefectLibraryService defectLibraryService,
                                    ChatSessionService sessionService,
                                    JdbcTemplate jdbcTemplate) {
        this.defectLibraryService = defectLibraryService;
        this.sessionService = sessionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String objectType() {
        return DraftObjectType.DEFECT_WRITE.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        DraftLandContext ctx = DraftLandContext.open(draftRow, sessionService, objectMapper,
                "AI 缺陷草案内容无法解析，确认写入已拒绝（请重新让 AI 生成提案）");
        JsonNode body = ctx.body();
        long draftId = ctx.draftId(), sessionId = ctx.sessionId(), projectId = ctx.projectId();
        if (body.hasNonNull("toStatus")) {
            return landTransition(draftRow, projectId, body, operatorId);
        }
        Object targetRaw = draftRow.get("target_id");
        long targetId = targetRaw == null ? 0L : ((Number) targetRaw).longValue();
        return targetId > 0
                ? landEdit(draftRow, projectId, body, targetId, operatorId)
                : landCreate(draftId, projectId, body, operatorId);
    }

    /** Create: the session project owns the row; the produced id pins back onto the ledger. */
    private Integer landCreate(long draftId, long projectId, JsonNode body, long operatorId) {
        String title = DraftPayloadReads.text(body, "title");
        if (title == null) {
            throw new ServiceException(
                    "AI 缺陷草案缺少缺陷标题（草案载荷 title 为空），无法建单"
                            + "——请在右侧对话坞重新让 AI 生成带标题的缺陷提案，本卡无需再确认；"
                            + "也可在左侧菜单「缺陷库」页直接新建缺陷",
                    BizErrorCode.VALIDATION_ERROR);
        }
        var created = defectLibraryService.create(operatorId, new DefectCreateInput(
                projectId,
                idOrNull(body, "caseId"),
                idOrNull(body, "planId"),
                title,
                DraftPayloadReads.text(body, "description"),
                body.hasNonNull("severity") && body.get("severity").isNumber()
                        ? body.get("severity").asInt() : null,
                idOrNull(body, "assigneeId"),
                stepsJsonOrNull(body),
                DraftPayloadReads.text(body, "externalKey")));
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET target_id = ?, updated_at = now() "
                + "WHERE id = ?", created.id(), draftId);
        // The fresh row's version is the ledger value the human echoes next.
        return created.version();
    }

    /** Edit: the named row must live in the session project, then the shared save chain runs. */
    private Integer landEdit(Map<String, Object> draftRow, long projectId, JsonNode body,
                             long defectId, long operatorId) {
        requireSameProject(defectId, projectId);
        String title = DraftPayloadReads.text(body, "title");
        if (title == null) {
            throw new ServiceException(
                    "AI 缺陷编辑草案缺少缺陷标题（草案载荷 title 为空），缺陷 #" + defectId + " 的改名未执行"
                            + "——请在右侧对话坞重新让 AI 生成带标题的编辑提案，本卡无需再确认；"
                            + "也可在左侧菜单「缺陷库」页打开该缺陷直接编辑",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Integer baseVersion = ((Number) draftRow.get("base_version")).intValue();
        var updated = defectLibraryService.update(operatorId, defectId, new DefectUpdateInput(
                title,
                DraftPayloadReads.text(body, "description"),
                body.hasNonNull("severity") && body.get("severity").isNumber()
                        ? body.get("severity").asInt() : null,
                idOrNull(body, "assigneeId"),
                stepsJsonOrNull(body),
                DraftPayloadReads.text(body, "externalKey"),
                baseVersion));
        return updated.version();
    }

    /** Transition: same cross-project re-check, then the shared state-machine chain. */
    private Integer landTransition(Map<String, Object> draftRow, long projectId, JsonNode body,
                                   long operatorId) {
        long defectId = body.path("defectId").asLong(0L);
        Object targetRaw = draftRow.get("target_id");
        long anchored = targetRaw == null ? 0L : ((Number) targetRaw).longValue();
        if (defectId <= 0 || anchored != defectId) {
            // A payload that moved away from the anchor the card was emitted
            // with is a tampered snapshot — refuse instead of guessing.
            throw new ServiceException(
                    "AI 缺陷流转草案的目标缺陷与草案卡锚点不一致（草案载荷 defectId=" + defectId
                            + "，草案卡 targetId=" + anchored + "），流转未执行"
                            + "——本卡请勿再确认，请在右侧对话坞重新让 AI 生成流转提案；"
                            + "也可在左侧菜单「缺陷库」页对该缺陷点「流转」按钮直接操作",
                    BizErrorCode.VALIDATION_ERROR);
        }
        requireSameProject(defectId, projectId);
        Integer baseVersion = ((Number) draftRow.get("base_version")).intValue();
        var moved = defectLibraryService.transition(operatorId, defectId, new DefectTransitionInput(
                body.get("toStatus").asInt(),
                DraftPayloadReads.text(body, "closedReason"),
                baseVersion));
        return moved.version();
    }

    /** H1: the defect row's own project must equal the session project. */
    private void requireSameProject(long defectId, long projectId) {
        long ownerProjectId = defectLibraryService.get(defectId).projectId();
        if (ownerProjectId != projectId) {
            throw new ServiceException(
                    "该草案指向的缺陷（缺陷 #" + defectId + "，归属项目 #" + ownerProjectId
                            + "）不属于当前会话所在项目（#" + projectId + "），确认写入已拒绝"
                            + "——请先在顶栏「选择项目」切换到该项目，"
                            + "再回到右侧对话坞重新生成提案或直接进入左侧菜单「缺陷库」页处理该缺陷",
                    BizErrorCode.FORBIDDEN);
        }
    }

    /** Blank or missing optional text reads as "not stated" (the chain defaults it). */
    /** Positive numeric id anchor or null (0/negative never reaches the chain). */
    private static Long idOrNull(JsonNode body, String field) {
        long value = body.path(field).asLong(0L);
        return value > 0 ? value : null;
    }

    /** Reproduction steps travel as the JSON array the page editor produces. */
    private static String stepsJsonOrNull(JsonNode body) {
        JsonNode steps = body.path("steps");
        if (steps.isArray() && !steps.isEmpty()) {
            return steps.toString();
        }
        String raw = body.path("stepsJson").asText("").trim();
        return raw.isEmpty() ? null : raw;
    }

}
