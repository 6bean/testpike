package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.component.DraftProjectAnchor;
import com.sixbean.testpike.core.aichat.domain.component.DraftPayloadReads;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.perf.domain.service.PerfRunService;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Perf-run launch strategy behind the generic draft door (tool-surface
 * clearing batch stage 5c, tenth cleared domain): one confirmed dialog
 * proposal presses a pressure run through the SAME
 * {@code PerfRunService.launch(projectId, scenarioId, envId, operatorId)}
 * transaction the workbench's launch dialog uses — the production-change
 * safety gate, the duration ceiling, the canary pre-flight and the GA-2
 * environment execution lock all re-run inside that transaction at launch
 * time, so the dialog is structurally unable to bypass one gate (red line:
 * the launch chain owns the decision, the draft card only states it).
 * A refusal there rolls the whole launch back and surfaces in the gate's own
 * human-readable wording — never reworded into a second spectrum here.
 * <p>Attribution guard (H1): the owning project is read from the chat
 * session, never from the payload. A model-forged scenario id therefore
 * cannot press another project's environment: the launch chain re-checks the
 * scenario belongs to the session project and refuses otherwise. The
 * optional launch-time environment override (GB-1) is passed through as-is;
 * an unknown or foreign env id is refused by the same single resolution
 * point inside launch, fail-closed with its own reason.
 * <p>A launch produces a run row, which has no version ledger for this kind:
 * the applied version stays null (mirroring the report kind's shape), while
 * target_id keeps the scenario anchor the card was emitted with — the object
 * the human confirmed an action ON. Creating or editing the scenario itself
 * stays a deliberate human flow on /perf: the wizard is a multi-step design
 * session, not a dialog payload.
 */
@Service
public class PerfLaunchDraftStrategy implements DraftWriteStrategy {

    private final PerfRunService perfRunService;
    private final DraftProjectAnchor draftProjectAnchor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PerfLaunchDraftStrategy(PerfRunService perfRunService,
                                   DraftProjectAnchor draftProjectAnchor) {
        this.perfRunService = perfRunService;
        this.draftProjectAnchor = draftProjectAnchor;
    }

    @Override
    public String objectType() {
        return DraftObjectType.PERF_LAUNCH.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        // H1 / AN batch piece 4a: the project segment comes from the
        // origin-aware anchor (single source: DraftProjectAnchor); the
        // payload only names the scenario and an optional launch-time
        // environment.
        long projectId = draftProjectAnchor.resolveForLanding(draftRow);
        JsonNode body = parseBody(String.valueOf(draftRow.get("payload_json")));
        long scenarioId = body.path("scenarioId").asLong(0L);
        if (scenarioId <= 0) {
            throw new ServiceException(
                    "AI 压测发起草案缺少场景编号 scenarioId，无法发起运行——请在「AI 对话」指定要运行的压测场景后重新生成提案；也可在「压测工作台」直接发起运行",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Long envId = body.hasNonNull("envId") ? body.get("envId").asLong() : null;
        // Every legality decision (scenario ownership, safety gate, ceiling,
        // canary, environment lock) is re-taken by the launch transaction
        // itself; this strategy only refuses a body that cannot reach it.
        perfRunService.launch(projectId, scenarioId, envId, operatorId);
        // A run row has no version ledger for this kind — honest null.
        return null;
    }

    private JsonNode parseBody(String payloadJson) {
        return DraftPayloadReads.parseOrRefuse(objectMapper, payloadJson, "AI 压测发起草案内容无法解析，确认发起已拒绝（请重新让 AI 生成提案）");
    }
}
