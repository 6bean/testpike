package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.component.DraftPayloadReads;
import com.sixbean.testpike.core.aichat.domain.component.DraftProjectAnchor;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.perf.api.dto.PerfApprovalInput;
import com.sixbean.testpike.core.perf.domain.service.PerfApprovalService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Re-approval landing strategy behind the generic draft door (AN ticket 4b).
 * One confirmed linkage card files a FRESH pending approval request through
 * the SAME {@code PerfApprovalService.apply} chain the approval page's
 * submit dialog uses — the environment-belongs-to-project check, the
 * concurrency positivity, the isolation-mode value set, the mandatory
 * reason and the fail-closed scenario anchor all re-decide inside that
 * chain, so this bean never carries a second copy of any validation or
 * refusal wording (red line: the write chain owns the decision, the draft
 * card only states it; R40 forbids a parallel approval-write path).
 * <p>History immutability: the card points at the original ticket only to
 * say which approval was overtaken by a scenario edit. Landing inserts a
 * new row and never touches the old one — the decided ticket stays the
 * audit fact the ledger recorded (TC-LNK-04).
 * <p>Attribution: system-origin linkage cards resolve the project from the
 * stored anchor column via {@link DraftProjectAnchor} (single source shared
 * with every other landing strategy, ticket 4a); dialog cards keep the
 * session door. The accepting human becomes the applicant of the fresh
 * request — the approver on it is still a separate deliberate decision.
 * <p>An approval request has no version ledger for this kind: the applied
 * version reads back null and target_id is rewritten to the produced fresh
 * ticket id, the object the human confirmed an action on.
 */
@Service
public class PerfReapprovalDraftStrategy implements DraftWriteStrategy {

    private final PerfApprovalService perfApprovalService;
    private final DraftProjectAnchor draftProjectAnchor;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PerfReapprovalDraftStrategy(PerfApprovalService perfApprovalService,
                                       DraftProjectAnchor draftProjectAnchor,
                                       JdbcTemplate jdbcTemplate) {
        this.perfApprovalService = perfApprovalService;
        this.draftProjectAnchor = draftProjectAnchor;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String objectType() {
        return DraftObjectType.PERF_APPROVAL.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        long draftId = ((Number) draftRow.get("id")).longValue();
        long projectId = draftProjectAnchor.resolveForLanding(draftRow);
        JsonNode body = parseBody(String.valueOf(draftRow.get("payload_json")));
        long scenarioId = body.path("scenarioId").asLong(0L);
        if (scenarioId <= 0) {
            throw new ServiceException("联动重审草案缺少所属压测场景编号 scenarioId，无法提交审批申请——请在「AI 对话」重新发起该场景的联动重审；也可在「压测工作台」选择场景后提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Every other field-legality decision (env in project, concurrency > 0,
        // isolation one-of, reason non-blank, scenario belongs to the project)
        // belongs to the apply chain — this door transports and files.
        long freshTicketId = perfApprovalService.apply(projectId, operatorId,
                new PerfApprovalInput(
                        body.hasNonNull("envId") ? body.path("envId").asLong() : null,
                        body.hasNonNull("requestedConcurrency")
                                ? body.path("requestedConcurrency").asInt() : null,
                        DraftPayloadReads.textOrNull(body, "isolationMode"),
                        DraftPayloadReads.textOrNull(body, "reason"),
                        scenarioId));
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET target_id = ?, updated_at = now() "
                + "WHERE id = ?", freshTicketId, draftId);
        return null;
    }

    private JsonNode parseBody(String payloadJson) {
        return DraftPayloadReads.parseOrRefuse(objectMapper, payloadJson, "联动重审草案内容无法解析，确认写入已拒绝（请重新让 AI 生成提案）");
    }
}
