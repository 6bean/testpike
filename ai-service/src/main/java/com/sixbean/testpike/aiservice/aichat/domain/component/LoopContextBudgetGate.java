package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ChatMessageRemote;
import com.sixbean.testpike.aiservice.llm.api.dto.AssistantToolCall;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;
import com.sixbean.testpike.aiservice.llm.domain.component.PromptSizeGuard;
import com.sixbean.testpike.aiservice.llm.domain.component.TokenEstimator;
import com.sixbean.testpike.aiservice.llm.domain.component.TokenEstimatorRegistry;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Per-round prompt-budget gate for dialog loops , AP-2;
 * over-budget self-heal extension item 7):
 * ReActRunner and ExploreLoopKernel rebuilt the whole transcript every
 * round with NO budget step, so an over-long packet failed the entire order
 * at the provider (or beyond) instead of degrading gracefully. This gate
 * runs right before every round: under the routed endpoint's prompt limit it
 * is a pass-through; over the limit the OLDER exchange units fold into one
 * rolling-summary section riding the leading system message — the exact AP-1
 * ② posture of {@link ConversationContextAssembler} (same headers, same
 * compressor seam, no second summariser).
 *
 * <p>Self-heal posture (adjudicated 2026-11-06: local pre-flight primary,
 * provider-400 summary retry as the safety net):
 * <ul>
 * <li>pre-flight — when {@code ai.ctx.over-budget-heal.enabled} (K8, ships
 * ON) is on, the early line sits at {@code ai.ctx.heal-preflight-ratio}
 * percent of the endpoint prompt limit and the estimate additionally
 * counts the tool schemas (function name + description + parameter JSON
 * ride in the same outbound packet — ignoring them understated every
 * function-calling round);</li>
 * <li>400 safety net — {@link #healAfterOverflow} folds the transcript once
 * even when the LOCAL config considered the packet fine (a differently
 * provisioned vendor enforces its own window); the switch OFF refuses
 * honestly with the enable path and the key named, a non-context 400 is
 * passed through untouched (folding cannot fix a malformed payload and
 * must not mask it).</li>
 * </ul>
 *
 * <p>Non-negotiable shapes enforced here:
 * <ul>
 * <li>the cut is unit-atomic — an assistant(tool_calls) message never
 * separates from its tool results (provider 400 precedent, AGENTS note);</li>
 * <li>the newest keepRecent units stay raw (K8 {@code ai.ctx.session-keep-recent-turns},
 * same key the turn assembler consumes — one budget vocabulary);</li>
 * <li>an over-budget packet that stays oversized after compression is
 * refused here instead of being sent: nothing foldable, a blank or
 * failed summary, or a post-fold packet still over the limit all stop
 * at this seam with the actual/limit numbers and the way out (new
 * conversation or shorter input) — a clear refusal beats a
 * non-deterministic provider failure;</li>
 * <li>routing failures fall through untouched — the fallback chain owns
 * the fail-closed "no provider" error, the gate never swallows it.</li>
 * </ul>
 */
@Component
public class LoopContextBudgetGate implements LoopContextFitting {

    private static final Logger log = LoggerFactory.getLogger(LoopContextBudgetGate.class);

    /** Observation cap inside a folded transcript row (summariser input sanity). */
    private static final int OBSERVATION_CAP_CHARS = 4000;

    private final ProjectLlmRouter router;
    private final AiCtxConfigResolver ctxResolver;
    private final DialogRollingSummary rollingSummary;

    public LoopContextBudgetGate(ProjectLlmRouter router, AiCtxConfigResolver ctxResolver,
                                 DialogRollingSummary rollingSummary) {
        this.router = router;
        this.ctxResolver = ctxResolver;
        this.rollingSummary = rollingSummary;
    }

    @Override
    public ChatCompletionRequest fit(ChatCompletionRequest request, long projectId) {
        List<ChatMessage> messages = request.messages();
        if (messages == null || messages.size() < 3) {
            // nothing older to fold than the live exchange itself
            return request;
        }
        LlmEndpointConfig endpoint = routeOrSkip(projectId);
        if (endpoint == null) {
            return request;
        }
        TokenEstimator estimator = TokenEstimatorRegistry.resolve(
                endpoint.tokenizerFamily(), endpoint.modelName());
        PromptSizeGuard guard = new PromptSizeGuard(estimator);
        int est = guard.estimate(messages);
        if (ctxResolver.healEnabled()) {
            est += toolTokens(estimator, request.tools());
        }
        if (est <= effectiveThreshold(endpoint)) {
            return request;
        }
        return fold(request, projectId, endpoint, guard, messages, est);
    }

    /**
 * Context-overflow safety net : the caller (the per-round
 * ReAct/stream seam) hands over the rejection the vendor actually returned.
 * A context-length 400 that the local budget config did not foresee still
 * folds the transcript ONCE and the caller retries with the healed packet;
 * every other outcome rethrows the same error instance (byte-identical
 * legacy posture for fittings that cannot heal).
 */
    @Override
    public ChatCompletionRequest healAfterOverflow(ChatCompletionRequest sent, long projectId,
                                                   ServiceException cause) {
        if (!isContextOverflow(cause)) {
            throw cause;
        }
        if (!ctxResolver.healEnabled()) {
            throw new ServiceException(cause.getMessage() + "；"
                    + DenialGuidanceFrame.switchClosedAfterAttempt(
                            "对话上下文自愈", "本次回答的其他部分不受影响",
                            AiCtxConfigResolver.KEY_HEAL_ENABLED), cause.getCode());
        }
        List<ChatMessage> messages = sent.messages();
        if (messages == null || messages.size() < 3) {
            throw cause;
        }
        LlmEndpointConfig endpoint = routeOrSkip(projectId);
        if (endpoint == null) {
            throw cause;
        }
        TokenEstimator estimator = TokenEstimatorRegistry.resolve(
                endpoint.tokenizerFamily(), endpoint.modelName());
        PromptSizeGuard guard = new PromptSizeGuard(estimator);
        log.warn("Provider context overflow 400 triggered self-healing fold projectId={} err={}",
                projectId, cause.getMessage());
        return fold(sent, projectId, endpoint, guard, messages, guard.estimate(messages));
    }

    /**
     * Provider context-length rejection signature: an HTTP 400 whose detail
     * speaks about the window (context / token / length wording). Any other
     * 400 (malformed payload, unsupported field) is NOT an overflow signal —
     * folding would hide a real contract error.
     */
    private static boolean isContextOverflow(ServiceException cause) {
        if (cause == null || cause.getMessage() == null) {
            return false;
        }
        String message = cause.getMessage();
        if (!message.contains("（HTTP 400）")) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("context") || lower.contains("token") || lower.contains("length");
    }

    /**
     * Pre-flight line: hard endpoint limit when the heal switch is off
     * (byte-identical legacy posture); ratio × limit when on (K8 hot update,
     * clamped in the resolver). An unset limit never fires the fold.
     */
    private int effectiveThreshold(LlmEndpointConfig endpoint) {
        Integer limit = endpoint.promptLimitTokens();
        if (limit == null || limit <= 0) {
            return Integer.MAX_VALUE;
        }
        if (!ctxResolver.healEnabled()) {
            return limit;
        }
        return Math.max(1, (int) ((long) limit * ctxResolver.healPreflightRatio() / 100));
    }

    /** Serialized tool schema cost (name + description + parameter JSON) in tokens. */
    private static int toolTokens(TokenEstimator estimator, List<ChatToolDef> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        StringBuilder schemaText = new StringBuilder();
        for (ChatToolDef tool : tools) {
            if (tool == null || tool.function() == null) {
                continue;
            }
            schemaText.append(tool.function().name()).append(' ')
                    .append(tool.function().description() == null
                            ? "" : tool.function().description())
                    .append(tool.function().parameters() == null
                            ? "" : tool.function().parameters().toString())
                    .append('\n');
        }
        return estimator.countTexts(List.of(schemaText.toString()));
    }

    /** Route for the budget read; unroutable projects skip the gate (chain fails closed). */
    private LlmEndpointConfig routeOrSkip(long projectId) {
        try {
            return router.route(projectId);
        } catch (ServiceException e) {
            log.warn("Loop context budget gate router lookup failed, sending the original packet this turn projectId={} err={}",
                    projectId, e.getMessage());
            return null;
        }
    }

    private ChatCompletionRequest fold(ChatCompletionRequest request, long projectId,
                                       LlmEndpointConfig endpoint, PromptSizeGuard guard,
                                       List<ChatMessage> messages, int estTokens) {
        int headEnd = 0;
        while (headEnd < messages.size() && "system".equals(messages.get(headEnd).role())) {
            headEnd++;
        }
        List<List<ChatMessage>> units = splitUnits(messages.subList(headEnd, messages.size()));
        if (units.size() <= 1) {
            refuseOverBudget(projectId, endpoint, estTokens, "无可折叠的更早轮次");
        }
        int keep = Math.max(1, ctxResolver.sessionKeepRecentTurns());
        int foldCount = Math.min(Math.max(1, units.size() - keep), units.size() - 1);
        List<ChatMessage> older = new ArrayList<>();
        for (int i = 0; i < foldCount; i++) {
            older.addAll(units.get(i));
        }
        List<ChatMessageRemote> rows = transcriptRows(older);
        String summary = summarize(request, endpoint, projectId, rows);
        if (summary == null || summary.isBlank()) {
            refuseOverBudget(projectId, endpoint, estTokens, "滚动摘要不可用");
        }
        List<ChatMessage> rebuilt = rebuildWithSummarySection(messages, headEnd, rows,
                summary.strip());
        for (int i = foldCount; i < units.size(); i++) {
            rebuilt.addAll(units.get(i));
        }
        PromptSizeGuard.Verdict post = guard.check(rebuilt, Integer.MAX_VALUE, true,
                endpoint.promptLimitTokens());
        if (post.decision() != PromptSizeGuard.Decision.PASS) {
            refuseOverBudget(projectId, endpoint, post.estTokens(), "折叠后仍超限");
        }
        log.info("Loop context budget gate compressed resubmit projectId={} est={} limit={} foldedUnits={} keptRawTurns={}",
                projectId, estTokens, endpoint.promptLimitTokens(), foldCount,
                units.size() - foldCount);
        return new ChatCompletionRequest(request.model(), List.copyOf(rebuilt),
                request.temperature(), request.maxTokens(), request.tools(),
                request.toolChoice(), null, null, null, request.responseFormat());
    }

    /**
     * Honest refusal for a packet that stays over budget with no way to
     * compress: the caller's round stops here with actionable wording
     * (actual/limit numbers + the way out), never a blind send the provider
     * turns into a non-deterministic failure.
     */
    private void refuseOverBudget(long projectId, LlmEndpointConfig endpoint,
                                  int est, String reason) {
        log.warn("Loop context budget gate refused projectId={} reason={} est={} limit={}",
                projectId, reason, est, endpoint.promptLimitTokens());
        throw new ServiceException("对话上下文超出模型上限（实际 " + est + " tokens > 上限 "
                + endpoint.promptLimitTokens() + "），无法继续本轮对话，"
                + "请新开对话或缩短本轮输入", BizErrorCode.AI_PROMPT_TOO_LARGE);
    }

    private String summarize(ChatCompletionRequest request, LlmEndpointConfig endpoint,
                             long projectId, List<ChatMessageRemote> rows) {
        String model = request.model() == null || request.model().isBlank()
                ? endpoint.modelName() : request.model();
        try {
            return rollingSummary.forTurn(model, projectId).summarize(rows);
        } catch (RuntimeException e) {
            log.warn("Loop summary compression unavailable, over-limit packet turned into refusal: {}", e.toString());
            return null;
        }
    }

    /** Leading system head + summary section (same headers as the turn assembler). */
    private List<ChatMessage> rebuildWithSummarySection(List<ChatMessage> messages, int headEnd,
                                                        List<ChatMessageRemote> rows,
                                                        String summary) {
        int turns = (int) rows.stream().filter(row -> "user".equals(row.role())).count();
        if (turns == 0) {
            turns = rows.size();
        }
        String section = ConversationContextAssembler.SUMMARY_HEADER_PREFIX + turns
                + ConversationContextAssembler.SUMMARY_HEADER_SUFFIX + summary;
        List<ChatMessage> rebuilt = new ArrayList<>();
        if (headEnd == 0) {
            rebuilt.add(new ChatMessage("system", section.strip()));
        } else {
            String headContent = messages.get(0).content() == null
                    ? "" : messages.get(0).content();
            rebuilt.add(new ChatMessage(messages.get(0).role(), headContent + section));
            for (int i = 1; i < headEnd; i++) {
                rebuilt.add(messages.get(i));
            }
        }
        return rebuilt;
    }

    /**
     * Unit split: a tool result always rides its preceding assistant unit —
     * cutting between them would break the provider's assistant/tool pairing
     * contract. Any non-tool message opens a new unit; an orphan leading tool
     * (defensive) opens its own so it still folds whole.
     */
    private static List<List<ChatMessage>> splitUnits(List<ChatMessage> tail) {
        List<List<ChatMessage>> units = new ArrayList<>();
        List<ChatMessage> current = null;
        for (ChatMessage message : tail) {
            if (current == null || !"tool".equals(message.role())) {
                current = new ArrayList<>();
                units.add(current);
            }
            current.add(message);
        }
        return units;
    }

    /**
 * Mirror rows for the SINGLE dialog compressor: one row per unit, tool
 * observations merged into the owning assistant row (the summariser
 * as user turns).
 */
    private static List<ChatMessageRemote> transcriptRows(List<ChatMessage> older) {
        List<ChatMessageRemote> rows = new ArrayList<>();
        for (ChatMessage message : older) {
            String role = message.role();
            if ("assistant".equals(role)) {
                StringBuilder content = new StringBuilder(message.textContent());
                if (message.toolCalls() != null) {
                    for (AssistantToolCall call : message.toolCalls()) {
                        content.append("\n调用工具 ").append(call.function().name())
                                .append(" 参数 ").append(call.function().arguments());
                    }
                }
                rows.add(mirrorRow("assistant", content.toString()));
            } else if ("tool".equals(role)) {
                String observation = cap(message.textContent());
                int last = rows.size() - 1;
                if (last >= 0 && "assistant".equals(rows.get(last).role())) {
                    ChatMessageRemote owner = rows.remove(last);
                    rows.add(mirrorRow("assistant",
                            owner.content() + "\n工具结果 " + observation));
                } else {
                    rows.add(mirrorRow("assistant", "工具结果 " + observation));
                }
            } else {
                rows.add(mirrorRow(role, message.textContent()));
            }
        }
        return rows;
    }

    private static ChatMessageRemote mirrorRow(String role, String content) {
        return new ChatMessageRemote(0, 0, role, content, null, "done",
                null, null, 0, 0, null, null, null);
    }

    private static String cap(String text) {
        if (text.length() <= OBSERVATION_CAP_CHARS) {
            return text;
        }
        return text.substring(0, OBSERVATION_CAP_CHARS) + "…（超长观测已截断）";
    }
}
