package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolFunction;

import java.util.List;

/**
 * Built-in chat.clarify FC tool of the live dialog (AX batch ,
 * the honest-suspension channel). Same synthetic dialog-surface shape as
 * {@link PlanProposeTool}: the streaming loop mounts the definition on every
 * streamed plan and routes the call straight here, never through the
 * registered-tool dispatcher — a clarification EXECUTES nothing.
 * <p>Posture: the model may stop and ask only when a key slot is missing and
 * the cost of guessing wrong is high; low-risk ambiguity must be answered
 * under a stated assumption instead (contract ⑥ of the AX adjudication —
 * the mechanism-side lock against the channel growing into nagging). When a
 * valid call lands, the ReAct loop ends the turn on the spot: no second
 * provider call is burned answering on the user's behalf. The card itself is
 * journaled by the dialog orchestrator under the hot-updated per-turn budget
 * ({@link #readMaxPerTurn}); the user's pick arrives as a normal follow-up
 * message in a fresh turn, and the goal ledger stays untouched (no
 * execution ticket is consumed).
 */
public final class ClarifyTool {

    /** FC tool name (same dotted built-in vocabulary as plan.propose). */
    public static final String NAME = "chat.clarify";

    /** Hot-update key capping how many clarify cards one turn may journal. */
    public static final String KEY_PER_TURN_MAX = "ai.chat.clarify-per-turn-max";

    /** Reader fallback: the factory default of the seeded key (one card). */
    public static final int DEFAULT_PER_TURN_MAX = 1;

    /** A single option is a statement, not a question — refused by shape. */
    static final int MIN_OPTIONS = 2;

    /** More than four options breaks the ≤2-click disambiguation contract. */
    static final int MAX_OPTIONS = 4;

    /** Shared reader of the argument bodies (model output = untrusted input). */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PARAMETERS_JSON = """
            {"type":"object","properties":{
              "question":{"type":"string","description":"对当前任务的一行问题重述，用户看一眼即明白在问什么"},
              "options":{"type":"array","minItems":2,"maxItems":4,"items":{"type":"string",
                "description":"一种解释的一句话展开（动宾短句，点选即按该解释继续）"}},
              "missingSlot":{"type":"string","description":"缺失的关键槽位名，如 目标项目、时间范围"}},
            "required":["question","options"]}""";

    private static final String DESCRIPTION =
            "仅当任务缺少关键槽位、且不同解释的执行代价较大时，调用本工具向用户追问一次"
                    + "（question=问题重述，options=2-4 种解释的一句话展开，missingSlot=缺失槽位名）。"
                    + "低风险歧义禁止调用本工具：按最可能的解释直接作答，并在首行声明所用假设。"
                    + "调用本工具后本轮立即结束：不要再调用其他工具，也不要替用户作答；"
                    + "用户的点选或补充会作为下一条普通消息进入新一轮对话。";

    private ClarifyTool() {
    }

    /** Routing predicate for the ReAct loop (built-in, never dispatcher-routed). */
    public static boolean isClarifyCall(String name) {
        return NAME.equals(name);
    }

    /**
     * Per-turn card budget read (single source for every consumer of the
     * clarify gate): key absent, blank or unparsable value = the factory
     * default. A garbage value in the box must never take the clarify channel
     * — or the dialog — down (same fail-back posture as the plan-threshold
     * reader).
     */
    public static int readMaxPerTurn(CoreGateway gateway) {
        try {
            String raw = gateway.getSysConfig(KEY_PER_TURN_MAX);
            if (raw == null || raw.isBlank()) {
                return DEFAULT_PER_TURN_MAX;
            }
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException garbageValue) {
            return DEFAULT_PER_TURN_MAX;
        }
    }

    /** Restatement line of the question — empty string when absent or broken. */
    public static String readQuestion(String argumentsJson) {
        return readText(argumentsJson, "question");
    }

    /** Name of the missing key slot — empty string when absent or broken. */
    public static String readMissingSlot(String argumentsJson) {
        return readText(argumentsJson, "missingSlot");
    }

    /** Option sentences — blank entries filtered, empty list for broken bodies. */
    public static List<String> readOptions(String argumentsJson) {
        return ToolArgumentReader.readNonBlankTextArray(MAPPER, argumentsJson, "options");
    }

    /**
     * Single source of the acceptance predicate (R40): a body is a card only
     * with a non-blank restatement and 2-4 non-blank options. The loop calls
     * this on execute, the orchestrator calls it again on journal — the two
     * sides can never disagree about which transcript entries are cards.
     */
    public static boolean isAcceptedShape(String argumentsJson) {
        String question = readQuestion(argumentsJson);
        List<String> options = readOptions(argumentsJson);
        return !question.isBlank() && options.size() >= MIN_OPTIONS
                && options.size() <= MAX_OPTIONS;
    }

    /**
     * Tool result of a clarification attempt: nothing executed. A body that
     * carries its question and 2-4 options is accepted and steers the loop to
     * end the turn; anything else is refused so the model either corrects
     * against the schema or answers under a stated assumption — a half-shape
     * card would bait a click that clarifies nothing.
     */
    public static ToolCallOutcome clarify(String argumentsJson) {
        if (!isAcceptedShape(argumentsJson)) {
            return ToolCallOutcome.fail(NAME,
                    "追问参数不合预期（question 须为非空重述，options 须为 2-4 条非空解释），"
                            + "本次未记录追问；请按最可能的解释直接作答并在首行声明所用假设");
        }
        int optionCount = readOptions(argumentsJson).size();
        return ToolCallOutcome.ok(NAME,
                "追问卡已记录（共 " + optionCount + " 个选项），本轮到此结束；"
                        + "不要再调用任何其他工具，也不要替用户作答——"
                        + "用户的点选或补充会作为下一条消息进入新一轮对话。");
    }

    /** Definition mounted on every streamed dialog plan (server-side synthetic). */
    public static ChatToolDef toolDef() {
        try {
            return new ChatToolDef(new ChatToolFunction(NAME, DESCRIPTION,
                    MAPPER.readTree(PARAMETERS_JSON)));
        } catch (Exception brokenSchema) {
            // A broken schema here is a development bug, not runtime state —
            // same posture as PlanProposeTool.toolDef.
            throw new IllegalStateException("built-in chat.clarify schema is invalid",
                    brokenSchema);
        }
    }

    /** One textual slot of the argument body; "" for absent/non-text/broken. */
    private static String readText(String argumentsJson, String field) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return "";
        }
        try {
            var node = MAPPER.readTree(argumentsJson).path(field);
            return node.isTextual() ? node.asText().trim() : "";
        } catch (Exception hallucinatedBody) {
            return "";
        }
    }
}
