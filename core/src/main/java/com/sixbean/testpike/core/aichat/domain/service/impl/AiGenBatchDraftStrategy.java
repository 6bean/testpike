package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiGenMessage;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.component.DraftPayloadReads;
import com.sixbean.testpike.core.aichat.domain.component.DraftLandContext;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.ai.api.dto.AiBatchCreateInput;
import com.sixbean.testpike.core.ai.api.dto.AiBatchCreateResult;
import com.sixbean.testpike.core.ai.domain.service.AiBatchService;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI-generation batch submit strategy behind the generic draft door (tool-
 * surface clearing batch stage 9, fifteenth cleared domain): one
 * confirmed dialog proposal queues a case-generation batch through the SAME
 * {@code AiBatchService.createBatch} + per-item MQ publish chain the
 * generation page's batch dialog runs — the requirement list validator
 * (non-empty lines, the per-line length ceiling, the K8-hot batch ceiling
 * {@code ai.batch.max-count}) re-decides inside that transaction at landing
 * time, so the dialog is structurally unable to bypass one gate (red line:
 * the enqueue chain owns the decision, the draft card only states it).
 * <p>Attribution guard (H1): the owning project is read from the chat
 * session, never from the payload — the input record carries no project
 * field the model can steer, so a forged call cannot queue generation in
 * another project's lane.
 * <p>Confirming the card only QUEUES generation (rows land as pending tasks
 * and MQ messages): produced cases land in the review state machine for the
 * human review loop — the dialog never auto-approves a case, which the card
 * states up front. The produced batch id is pinned back onto the ledger row
 * as target_id so the human can trace the batch progress afterwards; a batch
 * has no version ledger for this kind (applied version = null).
 */
@Service
public class AiGenBatchDraftStrategy implements DraftWriteStrategy {

    private final AiBatchService aiBatchService;
    private final AiGenPublisher aiGenPublisher;
    private final ChatSessionService sessionService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiGenBatchDraftStrategy(AiBatchService aiBatchService,
                                   AiGenPublisher aiGenPublisher,
                                   ChatSessionService sessionService,
                                   JdbcTemplate jdbcTemplate) {
        this.aiBatchService = aiBatchService;
        this.aiGenPublisher = aiGenPublisher;
        this.sessionService = sessionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String objectType() {
        return DraftObjectType.AIGEN_BATCH_WRITE.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        DraftLandContext ctx = DraftLandContext.open(draftRow, sessionService, objectMapper,
                "AI 生成批次草案内容无法解析，确认提交已拒绝（请重新让 AI 生成提案）");
        JsonNode body = ctx.body();
        long draftId = ctx.draftId(), sessionId = ctx.sessionId(), projectId = ctx.projectId();
        List<String> requirements = readRequirements(body);
        if (requirements.isEmpty()) {
            throw new ServiceException(
                    "AI 生成批次草案没有可用的需求条目，无法提交生成（请重新让 AI 生成提案）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // The enqueue chain owns every legality decision (blank line, line
        // length, batch ceiling); this door only refuses a body that cannot
        // reach it, then replays the page's exact submit posture: create the
        // batch rows and publish one generation message per item.
        AiBatchCreateResult result = aiBatchService.createBatch(
                AiBatchCreateInput.fromTexts(projectId, operatorId, requirements));
        List<Long> taskIds = result.taskIds();
        for (int i = 0; i < taskIds.size(); i++) {
            aiGenPublisher.publish(new AiGenMessage(taskIds.get(i), projectId, operatorId, 1,
                    requirements.get(i), null, null));
        }
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET target_id = ?, updated_at = now() "
                + "WHERE id = ?", result.batchId(), draftId);
        // A batch has no version ledger for this kind — null.
        return null;
    }

    /** Requirement array → the trimmed non-blank list the chain accepts. */
    private static List<String> readRequirements(JsonNode body) {
        List<String> out = new ArrayList<>();
        JsonNode node = body.path("requirements");
        if (node.isArray()) {
            node.forEach(item -> {
                String text = item.asText("").trim();
                if (!text.isEmpty()) {
                    out.add(text);
                }
            });
        }
        return out;
    }

}
