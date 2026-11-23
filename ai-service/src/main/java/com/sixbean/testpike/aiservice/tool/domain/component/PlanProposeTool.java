package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolFunction;

import java.util.List;

/**
 * Built-in plan.propose FC tool of the live dialog (AO batch package 2,
 * the multi-step plan card). Same synthetic dialog-surface shape as
 * {@link AggregateQueryTool}: the streaming loop mounts the definition on
 * every plan and routes the call straight here, never through the
 * registered-tool dispatcher — proposing a plan EXECUTES nothing.
 * <p>Posture: when the model expects a long tool chain it must hand the step
 * list to this tool FIRST; the tool result tells it to stop taking actions so
 * the plan waits for the user's confirmation — never a chain that started
 * behind the card. The ledger card itself is journaled by the dialog
 * orchestrator against the hot-updated {@code ai.chat.plan-threshold} gate
 * (single reader, {@link #readThreshold}); the plan frame never lands.
 */
public final class PlanProposeTool {

    /** FC tool name (same dotted built-in vocabulary as aggregate.query). */
    public static final String NAME = "plan.propose";

    /** Hot-update key deciding where a chain counts as long (V346 seed = 3). */
    public static final String KEY_PLAN_THRESHOLD = "ai.chat.plan-threshold";

    /** Reader fallback: the factory default of the seeded key. */
    public static final int DEFAULT_THRESHOLD = 3;

    /** Shared reader/writer of the argument bodies (model output = untrusted input). */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PARAMETERS_JSON = """
            {"type":"object","properties":{
              "steps":{"type":"array","items":{"type":"string",
                "description":"一步的动宾短句，用户可勾选、可调整顺序"}}},
            "required":["steps"]}""";

    private static final String DESCRIPTION =
            "当本次任务预计需要连续执行多步工具调用（默认 3 步及以上，门槛可在系统设置热更）时，"
                    + "先调用本工具提交分步计划（steps=每步动宾短句）。提交后本轮不要再调用其他工具："
                    + "向用户说明计划并等待用户确认，确认后的执行由用户发起新一轮对话完成。"
                    + "步数不足或纯问答任务禁止调用本工具。";

    private PlanProposeTool() {
    }

    /** Routing predicate for the ReAct loop (built-in, never dispatcher-routed). */
    public static boolean isPlanCall(String name) {
        return NAME.equals(name);
    }

    /**
     * Threshold read (single source for every consumer of the plan gate):
     * key absent, blank or unparsable value = the factory default. A garbage
     * value in the box must never take the plan feature — or the dialog —
     * down (same fail-back posture as the other ai.chat.* readers).
     */
    public static int readThreshold(CoreGateway gateway) {
        try {
            String raw = gateway.getSysConfig(KEY_PLAN_THRESHOLD);
            if (raw == null || raw.isBlank()) {
                return DEFAULT_THRESHOLD;
            }
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException garbageValue) {
            return DEFAULT_THRESHOLD;
        }
    }

    /** Proposed step titles — empty list for an unparsable or shapeless body. */
    public static List<String> readSteps(String argumentsJson) {
        return ToolArgumentReader.readNonBlankTextArray(MAPPER, argumentsJson, "steps");
    }

    /** Definition mounted on every streamed dialog plan (server-side synthetic). */
    public static ChatToolDef toolDef() {
        try {
            return new ChatToolDef(new ChatToolFunction(NAME, DESCRIPTION,
                    MAPPER.readTree(PARAMETERS_JSON)));
        } catch (Exception brokenSchema) {
            // A broken schema here is a development bug, not runtime state —
            // same posture as AggregateQueryTool.parseParameters.
            throw new IllegalStateException("built-in plan.propose schema is invalid",
                    brokenSchema);
        }
    }

    /**
     * Tool result of a proposal: nothing executed. A parsable body steers the
     * model to explain the plan and stop; an unparsable one is refused so the
     * model corrects against the schema instead of hallucinating a card.
     */
    public static ToolCallOutcome propose(String argumentsJson) {
        List<String> steps = readSteps(argumentsJson);
        if (steps.isEmpty()) {
            return ToolCallOutcome.fail(NAME,
                    "计划参数不合预期（steps 须为非空字符串数组），本次未记录计划，"
                            + "请按参数格式重试或直接作答");
        }
        return ToolCallOutcome.ok(NAME,
                "计划已记录（共 " + steps.size() + " 步），等待用户确认后才执行；"
                        + "本轮请勿再调用其他工具，向用户简要说明计划即可。");
    }
}
