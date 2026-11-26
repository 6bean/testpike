package com.sixbean.testpike.core.perf.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.DesensitizeUtils;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioDraftRequest;
import com.sixbean.testpike.core.perf.domain.component.PerfAiSwitchGate;
import com.sixbean.testpike.core.perf.domain.component.PerfStepActionGate;
import com.sixbean.testpike.core.perf.domain.service.PerfScenarioDraftService;
import com.sixbean.testpike.core.perf.infrastructure.remote.client.AiServicePerfDraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI scenario drafting orchestration (B-11 .
 * <p>Flow: switch gate first (a closed point refuses in plain words naming the
 * exact key, with zero tokens spent — the ai-service leg keeps its own
 * second-line check only against bare internal-door hits) → plain-word request
 * parameters → one ai-service hop → per-step re-validation through
 * {@link PerfStepActionGate#stepViolation}, the SAME component the scenario
 * save gate uses (red line 6: trimming has exactly one single point in core;
 * a second spec on the wizard or the ai-service leg is forbidden).
 * <p>Trimming is honest, never silent: violating steps are dropped but their
 * count and the gate's own wording travel back with the draft, so the wizard
 * prefills only steps the save gate will accept and the user sees what was
 * discarded and why. The reply never auto-saves — a human confirms the wizard.
 */
@Service
public class PerfScenarioDraftServiceImpl implements PerfScenarioDraftService {

    private static final Logger log = LoggerFactory.getLogger(PerfScenarioDraftServiceImpl.class);

    private final PerfAiSwitchGate switchGate;
    private final AiServicePerfDraftClient draftClient;
    private final PerfStepActionGate stepActionGate;
    private final ObjectMapper objectMapper;

    public PerfScenarioDraftServiceImpl(PerfAiSwitchGate switchGate,
                                        AiServicePerfDraftClient draftClient,
                                        PerfStepActionGate stepActionGate,
                                        ObjectMapper objectMapper) {
        this.switchGate = switchGate;
        this.draftClient = draftClient;
        this.stepActionGate = stepActionGate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> draft(PerfScenarioDraftRequest request) {
        switchGate.requireOpen(PerfAiSwitchGate.POINT_SCENARIO_DRAFT);
        if (request.projectId() == null) {
            throw new ServiceException("压测场景草拟请求缺少项目号，无法路由模型与成本归属"
                    + "——请先在顶栏项目切换器选择项目，再重新发起「AI 草拟步骤」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (request.description() == null || request.description().isBlank()) {
            throw new ServiceException("压测场景草拟缺少压测意图描述，无素材不烧模型——请在「AI 草拟（可选）」"
                    + "输入框用一句话写清意图（如：验证订单查询接口 200 并发下 10 分钟稳定），再点「AI 草拟步骤」",
                    BizErrorCode.VALIDATION_ERROR);
        }

        Map<String, Object> reply = draftClient.draft(request);
        PerfDraftTrimResult trimmed = trimToSaveAcceptedSteps(reply == null ? null : reply.get("steps"));
        log.info("压测场景草拟编排完成 projectId={} accepted={} dropped={}",
                request.projectId(), trimmed.steps().size(), trimmed.reasons().size());

        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("steps", trimmed.steps());
        draft.put("droppedCount", trimmed.reasons().size());
        draft.put("droppedReasons", trimmed.reasons());
        return draft;
    }

    /**
     * Keep only steps the scenario save gate would accept; every drop carries
     * the gate's own plain-word reason (single message corpus — the wizard shows
     * exactly what a later save would have said). A reply without a steps array
     * degrades to an honest empty draft with zero claimed drops: nothing was
     * trimmed, the model just answered off-contract.
     */
    private PerfDraftTrimResult trimToSaveAcceptedSteps(Object rawSteps) {
        List<Map<String, Object>> kept = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        if (rawSteps instanceof List<?> list) {
            int index = 0;
            for (Object item : list) {
                index++;
                if (!(item instanceof Map<?, ?> map)) {
                    reasons.add("第 " + index + " 步不是 JSON 对象，草稿不采用");
                    continue;
                }
                JsonNode step = objectMapper.valueToTree(map);
                String violation = stepActionGate.stepViolation(step);
                if (violation == null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> asMap = (Map<String, Object>) map;
                    kept.add(maskedStepHeaders(asMap));
                } else {
                    reasons.add("第 " + index + " 步已剔除：" + violation);
                }
            }
        }
        return new PerfDraftTrimResult(kept, reasons);
    }

    /**
     * U-10 read-out mask for the draft prefill: an accepted step that carries a
     * headers map gets its auth-shaped values star-valued through the shared
     * {@link DesensitizeUtils} name spectrum before the wizard renders them —
     * a model reply may echo a secret back, and the draft must never prefill one
     * visibly. Ordinary headers pass through untouched; the step object is
     * shallow-copied so the raw reply never mutated here keeps its shape honest.
     */
    private Map<String, Object> maskedStepHeaders(Map<String, Object> step) {
        if (!(step.get("headers") instanceof Map<?, ?> headers)) {
            return step;
        }
        Map<String, Object> masked = new LinkedHashMap<>(step);
        Map<String, Object> maskedHeaders = new LinkedHashMap<>();
        headers.forEach((name, value) -> maskedHeaders.put(String.valueOf(name),
                DesensitizeUtils.isSensitiveName(String.valueOf(name))
                        ? "***"
                        : value == null ? null : String.valueOf(value)));
        masked.put("headers", maskedHeaders);
        return masked;
    }
}
