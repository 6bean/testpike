package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Built-in chat.suggest FC tool of the live dialog (AX batch , the
 * proactive-extension channel): when the model finishes an answer it may
 * propose 1-3 next-step questions the user can pick up with one click.
 * Same dialog-surface injection shape as {@link ClarifyTool}: the streaming
 * loop mounts the definition on every streamed plan and routes the call
 * straight here, never through the registered-tool dispatcher — a suggestion
 * EXECUTES nothing and does NOT end the turn (the answer it closes is the
 * normal final answer, so unlike clarify the loop keeps running to its usual
 * finish).
 * <p>Trust posture: model output is untrusted. The orchestrator filters every
 * proposed capability against the turn's mounted tool intersection
 * (published∩enabled∩authorization) before the done frame is journaled —
 * a suggestion pointing at a capability the user cannot use is dropped
 */
public final class SuggestTool {

    /** FC tool name (same dotted built-in vocabulary as chat.clarify). */
    public static final String NAME = "chat.suggest";

    /** A turn that suggests anything suggests at least one next step. */
    static final int MIN_SUGGESTIONS = 1;

    /** More than three chips compete with the answer instead of extending it. */
    static final int MAX_SUGGESTIONS = 3;

    /** Shared reader of the argument bodies (model output = untrusted input). */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PARAMETERS_JSON = """
            {"type":"object","properties":{
              "suggestions":{"type":"array","minItems":1,"maxItems":3,"items":{"type":"object",
                "properties":{
                  "text":{"type":"string","description":"以用户口吻写的一条下一步问题（动宾短句，点选即按原样发出）"},
                  "capability":{"type":"string","description":"该问题将用到的能力名，必须是本轮工具清单里的名字"}},
                "required":["text","capability"]}}},
            "required":["suggestions"]}""";

    private static final String DESCRIPTION =
            "回答完成后，若存在用户大概率想继续的下一步，调用本工具提议 1-3 条下一步问题"
                    + "（text=以用户口吻写的一条问题，capability=该问题将用到的能力名，"
                    + "必须取自本轮可用工具清单；引用清单外能力的提议会被服务端整条丢弃）。"
                    + "本工具不执行任何操作、不会结束本轮：调用后照常给出最终回答即可。"
                    + "没有值得提议的下一步时不要调用，禁止为凑数反复提议。";

    private SuggestTool() {
    }

    /** Routing predicate for the ReAct loop (built-in, never dispatcher-routed). */
    public static boolean isSuggestCall(String name) {
        return NAME.equals(name);
    }

    /** One accepted proposal: the question sentence + the capability it names. */
    public record Suggestion(String text, String capability) {
    }

    /**
     * Proposed items of an argument body — blank texts filtered, empty list
     * for broken bodies. Entry shape (object with text+capability) is parsed
     * here once; both the loop and the journaling door read through this
     * single source (R40).
     */
    public static List<Suggestion> readSuggestions(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return List.of();
        }
        try {
            var node = MAPPER.readTree(argumentsJson).path("suggestions");
            if (!node.isArray()) {
                return List.of();
            }
            List<Suggestion> items = new ArrayList<>();
            for (var item : node) {
                String text = item.path("text").isTextual()
                        ? item.path("text").asText().trim() : "";
                String capability = item.path("capability").isTextual()
                        ? item.path("capability").asText().trim() : "";
                if (!text.isEmpty()) {
                    items.add(new Suggestion(text, capability));
                }
            }
            return List.copyOf(items);
        } catch (Exception hallucinatedBody) {
            return List.of();
        }
    }

    /**
     * Single source of the acceptance predicate (R40, same posture as
     * {@code ClarifyTool.isAcceptedShape}): 1-3 items with a non-blank
     * question sentence. The loop executes against this, the orchestrator
     * re-checks with it — the two sides can never disagree about which
     * transcript entries are suggestions.
     */
    public static boolean isAcceptedShape(String argumentsJson) {
        List<Suggestion> items = readSuggestions(argumentsJson);
        return items.size() >= MIN_SUGGESTIONS && items.size() <= MAX_SUGGESTIONS;
    }

    /**
     * Items of the FIRST accepted chat.suggest call of the finished
     * transcript (one chip set per turn, same reading posture as the clarify
     * card); empty for a null outcome or a transcript without a routable
     * accepted call. Refused shapes (garbage JSON, blank sentences, out of
     * the 1-3 bound) never surface as chips.
     */
    public static List<Suggestion> readAcceptedSuggestions(ReActOutcome outcome) {
        if (outcome == null || outcome.messages() == null) {
            return List.of();
        }
        for (ChatMessage message : outcome.messages()) {
            if (!"assistant".equals(message.role()) || message.toolCalls() == null) {
                continue;
            }
            for (var call : message.toolCalls()) {
                String name = call.function() == null ? null : call.function().name();
                if (!isSuggestCall(name)) {
                    continue;
                }
                String arguments = call.function().arguments();
                if (isAcceptedShape(arguments)) {
                    return readSuggestions(arguments);
                }
            }
        }
        return List.of();
    }

    /**
 * Server-side delivery gate (AX adjudication ): keep only proposals
 * whose capability rides inside the turn's mounted intersection. A blank
 * capability is dropped too — a chip nobody can route is not a suggestion.
 */
    public static List<Suggestion> filterAllowed(List<Suggestion> suggestions,
                                                 Set<String> allowedCapabilities) {
        if (suggestions == null || suggestions.isEmpty()
                || allowedCapabilities == null || allowedCapabilities.isEmpty()) {
            return List.of();
        }
        List<Suggestion> kept = new ArrayList<>();
        for (Suggestion suggestion : suggestions) {
            if (suggestion != null && allowedCapabilities.contains(suggestion.capability())) {
                kept.add(suggestion);
            }
        }
        return List.copyOf(kept);
    }

    /**
     * Tool result of a suggestion call: nothing executed, the turn is NOT
     * suspended (contrast with clarify). A valid shape acknowledges so the
     * model proceeds to its final answer; anything else is refused with the
     * correction hint so the loop self-fixes instead of baiting a dead chip.
     */
    public static ToolCallOutcome suggest(String argumentsJson) {
        if (!isAcceptedShape(argumentsJson)) {
            return ToolCallOutcome.fail(NAME,
                    "扩展建议参数不合预期（suggestions 须为 1-3 条非空条目），本次未记录建议；"
                            + "没有值得提议的下一步时直接给出最终回答即可");
        }
        int count = readSuggestions(argumentsJson).size();
        return ToolCallOutcome.ok(NAME,
                "扩展建议已记录 " + count + " 条，将在最终回答后以可点建议呈现给用户；"
                        + "本工具不执行任何操作，请照常给出最终回答。");
    }

    /** Definition mounted on every streamed dialog plan (server-side synthetic). */
    public static ChatToolDef toolDef() {
        try {
            return new ChatToolDef(new ChatToolFunction(NAME, DESCRIPTION,
                    MAPPER.readTree(PARAMETERS_JSON)));
        } catch (Exception brokenSchema) {
            // A broken schema here is a development bug, not runtime state —
            // same posture as ClarifyTool.toolDef.
            throw new IllegalStateException("built-in chat.suggest schema is invalid",
                    brokenSchema);
        }
    }
}
