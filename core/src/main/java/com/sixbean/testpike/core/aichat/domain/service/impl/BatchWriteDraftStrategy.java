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
import com.sixbean.testpike.core.batch.api.dto.BatchCreateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.batch.domain.service.BatchService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Batch-landing strategy behind the generic draft door (tool-surface clearing
 * batch stage 4b, sixth cleared domain): one confirmed dialog proposal
 * creates a draft execution batch through the SAME BatchService.create chain
 * the batches-page form uses — the blank-name refusal, the at-least-one-case
 * refusal, the environment-ownership check, the end-environment matching
 * (a web case never enters a pos-environment batch), the resource-pool tag
 * check and audit all come free, never a parallel write path (red line: no
 * second batch save).
 * <p>The batch keeps its human lifecycle after landing: the row is created in
 * draft state exactly like the form's create — starting it (the precheck
 * chain, the case-version snapshot and the task queue) and any later edit
 * stay deliberate human clicks on /batches; confirming a dialog card is
 * never a launch.
 * <p>Attribution guard (H1): the owning project comes from the SESSION, never
 * from the payload — a model-forged projectId or createdBy is simply not
 * consumed; the batch is created by the human who confirmed the card.
 * Environments and cases named in the payload are re-checked by BatchService
 * against that project, so cross-project ids cannot smuggle a batch into
 * someone else's setting.
 * <p>Only the kind's own anchors are validated here; every business rule
 * (case ownership, kernel whitelist, switch value ranges) stays single-sourced
 * in BatchServiceImpl — a payload it rejects surfaces in its own
 * human-readable wording, never reworded into a second spectrum.
 */
@Service
public class BatchWriteDraftStrategy implements DraftWriteStrategy {

    private final BatchService batchService;
    private final ChatSessionService sessionService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BatchWriteDraftStrategy(BatchService batchService,
                                   ChatSessionService sessionService,
                                   JdbcTemplate jdbcTemplate) {
        this.batchService = batchService;
        this.sessionService = sessionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String objectType() {
        return DraftObjectType.BATCH_WRITE.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        DraftLandContext ctx = DraftLandContext.open(draftRow, sessionService, objectMapper,
                "AI 批次草案内容无法解析，确认写入已拒绝（请重新让 AI 生成提案）");
        JsonNode body = ctx.body();
        long draftId = ctx.draftId(), sessionId = ctx.sessionId(), projectId = ctx.projectId();
        String name = DraftPayloadReads.text(body, "name");
        List<Long> caseIds = readLongList(body.get("caseIds"));
        if (name == null || name.isBlank() || caseIds.isEmpty()) {
            throw new ServiceException(
                    "AI 批次草案缺少批次名 name 或未勾选任何用例 caseIds，无法创建批次——请在「AI 对话」补全批次名并勾选要纳入的用例后重新生成提案",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // envId is optional here exactly like on the form (null = pick the
        // environment before starting); BatchService.create re-checks it
        // against the project along with case ownership and end matching.
        Long envId = body.hasNonNull("envId") ? body.get("envId").asLong() : null;
        BatchInfo created = batchService.create(operatorId, new BatchCreateInput(
                projectId, name.trim(), envId, caseIds, null,
                DraftPayloadReads.integer(body, "notifyFeishu"),
                body.hasNonNull("poolTagId") ? body.get("poolTagId").asLong() : null,
                DraftPayloadReads.text(body, "browser"), DraftPayloadReads.integer(body, "cleanAppData")));
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET target_id = ?, updated_at = now() "
                + "WHERE id = ?", created.id(), draftId);
        // A create has no separate applied version for this kind (null = none).
        return null;
    }

    private static List<Long> readLongList(JsonNode node) {
        List<Long> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                if (item.isNumber()) {
                    out.add(item.asLong());
                }
            });
        }
        return out;
    }

}
