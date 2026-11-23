package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.api.dto.ScenarioEnumerationResult;
import com.sixbean.testpike.aiservice.gen.api.dto.ScenarioItem;
import com.sixbean.testpike.aiservice.gen.api.dto.ScenarioKind;
import com.sixbean.testpike.aiservice.gen.domain.component.ScenarioListParser;
import com.sixbean.testpike.aiservice.gen.domain.component.ScenarioStrategy;
import com.sixbean.testpike.aiservice.gen.domain.service.ScenarioEnumerationService;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.RecallDensityGate;
import com.sixbean.testpike.aiservice.llm.domain.component.RegenerationFeedback;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans;
import com.sixbean.testpike.aiservice.llm.pipeline.DeliberationLoop;
import com.sixbean.testpike.aiservice.llm.pipeline.DeliberationOutcome;
import com.sixbean.testpike.aiservice.llm.pipeline.RecallDirectiveSwitch;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage0 scenario enumeration wired through the shared kernel deliberation ring
 * ; single-cycle-source discipline — this service adds a
 * reasoning consumer, never a second loop).
 * <p>Role wiring: proposer = one LLM enumeration pass over the requirement line
 * with the priority kind brief; verifier = the strict {@link ScenarioListParser}
 * rejection reasons folded together with the {@link ScenarioStrategy} mandatory
 * kind gaps (both existing rule sources, no new judgement rule); reviser = a
 * targeted second pass that folds only the named disputes back through the
 * shared {@link RegenerationFeedback} renderer. Round count reads the hot K8 row
 * {@code ai.kernel.deliberation-rounds} fresh per run.
 * <p>Outcome shape: the priority filter (P2 trims to main flow/equivalence) and
 * the per-requirement cap row both trim the selection preview; a draft that
 * still violates the contract after the ring answers with a named-reason
 * {@link ServiceException} (AI_FORMAT_ERROR, same failure vocabulary as the
 * generation stages) — never a silently half-baked list. The three human gates
 * (selection checkbox, adoption, cost confirmation) live entirely outside.
 */
@Service
public class ScenarioEnumerationServiceImpl implements ScenarioEnumerationService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioEnumerationServiceImpl.class);

    /** Contract header for the enumeration proposer/reviser system pass. */
    static final String ENUMERATION_SYSTEM = """
            你是测试场景枚举器，只负责列出场景清单，不产用例。
            严格输出 JSON：{"scenarios":[{"kind":"main-flow|equivalence|counter-example|boundary|state-combination","title":"场景名","anchor":"需求原文逐字片段","note":"可选补充"}]}。
            anchor 必须逐字摘自需求原文（无原文依据的场景属于虚构范围，会被整条拒绝）；除 JSON 外不要输出任何文本。""";

    private final LlmFallbackChain llmFallbackChain;
    private final ProjectLlmRouter projectLlmRouter;
    private final CoreGateway coreGateway;
    private final ScenarioListParser scenarioListParser;

    public ScenarioEnumerationServiceImpl(LlmFallbackChain llmFallbackChain,
                                          ProjectLlmRouter projectLlmRouter,
                                          CoreGateway coreGateway,
                                          ScenarioListParser scenarioListParser) {
        this.llmFallbackChain = llmFallbackChain;
        this.projectLlmRouter = projectLlmRouter;
        this.coreGateway = coreGateway;
        this.scenarioListParser = scenarioListParser;
    }

    @Override
    public ScenarioEnumerationResult enumerate(String requirementText, int priority, long projectId,
                                               Long operatorId, long taskId) {
        if (!SysConfigBooleans.on(coreGateway.getSysConfig(ScenarioStrategy.KEY_EXPAND_ENABLED))) {
            // Switch off = legacy one-case-per-line path, zero model calls, zero drift.
            return ScenarioEnumerationResult.disabled();
        }
        int cap = ScenarioStrategy.scenarioCap(
                coreGateway.getSysConfig(ScenarioStrategy.KEY_MAX_PER_REQUIREMENT));
        String kernelRoundsRaw = coreGateway.getSysConfig(DeliberationLoop.GOVERNANCE_KEY);
        LlmEndpointConfig endpoint = projectLlmRouter.route(projectId);
        LlmFallbackNotice notice = new LlmFallbackNotice(operatorId, projectId,
                "stage0 场景枚举任务#" + taskId);
        DeliberationOutcome ring = DeliberationLoop.deliberate(
                "按需求点原文枚举测试场景：" + ScenarioStrategy.proposerKindBrief(priority),
                List.of(requirementText),
                kernelRoundsRaw, 0,
                brief -> askEnumeration(endpoint, notice, projectId, requirementText, priority,
                        List.of()),
                (draft, evidence) -> disputesOnDraft(draft, priority),
                (draft, disputes) -> askEnumeration(endpoint, notice, projectId, requirementText,
                        priority, disputes));
        log.info("stage0 场景枚举推理确认环 taskId={} priority={} roundsUsed={}/{} modelCalls={} converged={} stalled={}",
                taskId, priority, ring.roundsUsed(), ring.roundsPlanned(), ring.modelCalls(),
                ring.converged(), ring.stalled());
        List<ScenarioItem> parsed;
        try {
            parsed = scenarioListParser.parse(ring.draft());
        } catch (IllegalArgumentException rejected) {
            throw new ServiceException("场景枚举输出校验失败（推理确认环 " + ring.roundsUsed()
                    + " 轮残余：" + String.join("；", ring.residualClaims()) + "）",
                    BizErrorCode.AI_FORMAT_ERROR);
        }
        List<ScenarioItem> selection = ScenarioStrategy.cap(
                ScenarioStrategy.retainedForPriority(parsed, priority), cap);
        if (!ring.converged()) {
            log.warn("stage0 场景枚举未收敛，残余分歧随预览圈选亮出 taskId={} 残余={}",
                    taskId, ring.residualClaims());
        }
        recallDensityCheck(taskId, selection.size(),
                RecallDensityGate.estimateTokensFromChars(requirementText.length()));
        return new ScenarioEnumerationResult(true, selection, ring.residualClaims(),
                ring.roundsUsed(), ring.modelCalls(), ring.converged());
    }

    /** piece 2: the enumeration return point consumes the shared
 * RecallDensityGate verdict so its hot key has a real consumer. The
 * V503 calibration booked this plane at 0 (scenario rows are not
 * density-comparable with per-item planes), and 0 is the honest
 * check-off; measurement stays the scenario-item count with the gate's
 * char→estimated-token helper as denominator. */
    static final String KEY_RECALL_DENSITY_FLOOR =
            "ai.gen.scenario-recall-density-floor-per-ktoken";
    static final double DEFAULT_RECALL_DENSITY_FLOOR = 0.0;

    /** WARN one honest numbers line when an explicitly enabled floor falls
     *  short; the booked 0 default answers silent (zero extra calls). */
    private void recallDensityCheck(long taskId, int itemCount, int estimatedTokens) {
        double floor = RecallDensityGate.parseFloor(
                coreGateway.getSysConfig(KEY_RECALL_DENSITY_FLOOR),
                DEFAULT_RECALL_DENSITY_FLOOR);
        if (RecallDensityGate.missed(floor, itemCount, estimatedTokens)) {
            log.warn("场景枚举密度下限闸 taskId={} 实收={} 条数下限={} 源token={}",
                    taskId, itemCount,
                    RecallDensityGate.expectedItems(estimatedTokens, floor), estimatedTokens);
        }
    }

    /**
     * Verifier segment: contract rejection reasons first (a draft that does not
     * even parse can only be judged as a whole), otherwise the mandatory kind
     * gaps the priority demands — both folded back as named disputes for the
     * reviser, empty list = converged.
     */
    private List<String> disputesOnDraft(String draft, int priority) {
        List<String> disputes = new ArrayList<>();
        List<ScenarioItem> items;
        try {
            items = scenarioListParser.parse(draft);
        } catch (IllegalArgumentException malformed) {
            disputes.add("场景清单不合契约：" + malformed.getMessage());
            return disputes;
        }
        for (ScenarioKind missing : ScenarioStrategy.missingMandatoryKinds(priority, items)) {
            disputes.add("缺少该优先级必须覆盖的场景类型：" + missing.code() + "，请定向补齐该型场景");
        }
        return disputes;
    }

    /** Proposer/reviser segment: one model pass, disputes through the shared renderer. */
    private String askEnumeration(LlmEndpointConfig endpoint, LlmFallbackNotice notice,
                                  long projectId, String requirementText, int priority,
                                  List<String> disputes) {
        String user = "优先级场景扩列指令：" + ScenarioStrategy.proposerKindBrief(priority) + "\n\n"
                + "需求点原文：\n" + requirementText + "\n\n"
                + RegenerationFeedback.render(disputes, "上一轮清单未通过核对，请只针对以下缺项定向修订：");
        return llmFallbackChain.chat(new ChatCompletionRequest(
                endpoint.modelName(), List.of(
                new ChatMessage("system", ENUMERATION_SYSTEM + recallSegment()),
                new ChatMessage("user", user)), null, null),
                projectId, notice).content();
    }

    /**
 * per-domain hot switch for the exhaustive-recall clause block on
 * the stage-0 scenario enumeration face (fail-soft ON, read single-sourced
 * in RecallDirectiveSwitch; OFF keeps the contract header byte-identical).
 */
    public static final String KEY_RECALL_DIRECTIVE_ENABLED = "ai.gen.scenario-recall-directive.enabled";

    /** Recall segment appended to the enumeration system message (zero bytes off). */
    String recallSegment() {
        return RecallDirectiveSwitch.segment(
                coreGateway.getSysConfig(KEY_RECALL_DIRECTIVE_ENABLED), KEY_RECALL_DIRECTIVE_ENABLED);
    }
}
