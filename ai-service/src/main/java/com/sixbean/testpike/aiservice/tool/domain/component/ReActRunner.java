package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmStreamEvent;
import com.sixbean.testpike.aiservice.llm.pipeline.StallFingerprintGuard;
import com.sixbean.testpike.aiservice.llm.pipeline.StallVerdict;
import com.sixbean.testpike.aiservice.mcp.domain.component.McpTaskToolBag;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolFcAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.tool.domain.FcPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;


@Component
public class ReActRunner {

    private static final Logger log = LoggerFactory.getLogger(ReActRunner.class);

    private final LlmFallbackChain llmFallbackChain;
    private final LlmFunctionCalling functionCalling;
    private final ToolDispatcher dispatcher;
    private final FcRoundPolicy roundPolicy;
    /** Dialog-surface built-in tool . */
    private final AggregateQueryTool aggregateQueryTool;
    /** AP-2 per-round prompt-budget fitting : folds the oversized
 * loop packet via the rolling summary before EVERY provider round instead
 * of letting it fail the whole order at the provider. */
    private final com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting
            loopFitting;
    /** AP-1(1) built-in retrieval meta-tool ③; nullable bean =
 * folded pointers simply stay unretrievable, the loop keeps legacy shape). */
    private final ToolResultRetrievalTool retrievalTool;
    /** AP-5 catalog meta-tool ; nullable bean = no search_tools
 * routing — a hallucinated name stays a dispatcher refuse, legacy shape). */
    private final FcToolCatalog catalog;
    /** Read-only fan-out (behavior-kernel tenet 10 / ; null bean = every
 * call keeps the legacy one-at-a-time route, zero drift for the tests and
 * for any wiring that predates the fan-out). */
    private final ReadOnlyToolFanout fanout;
    /** Stall reflection ( item 8 wiring; null bean = the loop dispatches
 * every call unguarded - the legacy shape all pre-item-8 wiring rides). */
    private final StallBlockPolicy stallPolicy;

    public ReActRunner(LlmFallbackChain llmFallbackChain, LlmFunctionCalling functionCalling,
                       ToolDispatcher dispatcher, FcRoundPolicy roundPolicy,
                       AggregateQueryTool aggregateQueryTool) {
        this(llmFallbackChain, functionCalling, dispatcher, roundPolicy, aggregateQueryTool,
                com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting.NONE, null);
    }

    public ReActRunner(LlmFallbackChain llmFallbackChain, LlmFunctionCalling functionCalling,
                       ToolDispatcher dispatcher, FcRoundPolicy roundPolicy,
                       AggregateQueryTool aggregateQueryTool,
                       com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting
                               loopFitting) {
        this(llmFallbackChain, functionCalling, dispatcher, roundPolicy, aggregateQueryTool,
                loopFitting, null);
    }

    public ReActRunner(LlmFallbackChain llmFallbackChain, LlmFunctionCalling functionCalling,
                       ToolDispatcher dispatcher, FcRoundPolicy roundPolicy,
                       AggregateQueryTool aggregateQueryTool,
                       com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting
                               loopFitting,
                       ToolResultRetrievalTool retrievalTool) {
        this(llmFallbackChain, functionCalling, dispatcher, roundPolicy, aggregateQueryTool,
                loopFitting, retrievalTool, null);
    }

    public ReActRunner(LlmFallbackChain llmFallbackChain, LlmFunctionCalling functionCalling,
                       ToolDispatcher dispatcher, FcRoundPolicy roundPolicy,
                       AggregateQueryTool aggregateQueryTool,
                       com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting
                               loopFitting,
                       ToolResultRetrievalTool retrievalTool,
                       FcToolCatalog catalog) {
        this(llmFallbackChain, functionCalling, dispatcher, roundPolicy, aggregateQueryTool,
                loopFitting, retrievalTool, catalog, null);
    }

    public ReActRunner(LlmFallbackChain llmFallbackChain, LlmFunctionCalling functionCalling,
                       ToolDispatcher dispatcher, FcRoundPolicy roundPolicy,
                       AggregateQueryTool aggregateQueryTool,
                       com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting
                               loopFitting,
                       ToolResultRetrievalTool retrievalTool,
                       FcToolCatalog catalog,
                       ReadOnlyToolFanout fanout) {
        this(llmFallbackChain, functionCalling, dispatcher, roundPolicy, aggregateQueryTool,
                loopFitting, retrievalTool, catalog, fanout, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ReActRunner(LlmFallbackChain llmFallbackChain, LlmFunctionCalling functionCalling,
                       ToolDispatcher dispatcher, FcRoundPolicy roundPolicy,
                       AggregateQueryTool aggregateQueryTool,
                       com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting
                               loopFitting,
                       ToolResultRetrievalTool retrievalTool,
                       FcToolCatalog catalog,
                       ReadOnlyToolFanout fanout,
                       StallBlockPolicy stallPolicy) {
        this.llmFallbackChain = llmFallbackChain;
        this.functionCalling = functionCalling;
        this.dispatcher = dispatcher;
        this.roundPolicy = roundPolicy;
        this.aggregateQueryTool = aggregateQueryTool;
        this.loopFitting = loopFitting;
        this.retrievalTool = retrievalTool;
        this.catalog = catalog;
        this.fanout = fanout;
        this.stallPolicy = stallPolicy;
    }

    
    public ReActOutcome run(ChatCompletionRequest baseRequest, long projectId,
                            LlmFallbackNotice notice, Long actorId, Long taskId,
                            McpTaskToolBag mcpBag) {
        return run(baseRequest, projectId, notice, actorId, taskId, mcpBag, null);
    }

    /**
 * Conversation-carrying variant : {@code chatSessionId}
 * is the server-side conversation segment threaded to the dispatcher so the
 * dialog OCR read doors get their stamped trio; the async generation link
 * keeps the 6-arg form (session = null → forged anchors are stripped).
 */
    public ReActOutcome run(ChatCompletionRequest baseRequest, long projectId,
                            LlmFallbackNotice notice, Long actorId, Long taskId,
                            McpTaskToolBag mcpBag, Long chatSessionId) {
        return runInternal(baseRequest, projectId, notice, actorId, taskId, mcpBag,
                chatSessionId, null);
    }

    /**
 * Streaming variant : same orchestration single
 * point, but provider deltas and tool dispatch brackets are pushed to
 * {@code sink} as they happen — the dialog orchestrator turns them into
 * SSE frames. The loop body is shared with {@link #run} (no second copy,
 * no rewrite), so round accounting, tool backfill pairs and the
 * limitReached exit keep identical semantics.
 */
    public ReActOutcome runStreaming(ChatCompletionRequest baseRequest, long projectId,
                                     LlmFallbackNotice notice, Long actorId, Long taskId,
                                     McpTaskToolBag mcpBag, ReActStreamSink sink) {
        return runInternal(baseRequest, projectId, notice, actorId, taskId, mcpBag, null, sink);
    }

    /**
 * Session-carrying streaming variant : the orchestrator hands
 * over the live conversation id so read doors anchored to it (dialog OCR)
 * receive a server-stamped trio instead of a model-claimed one.
 */
    public ReActOutcome runStreaming(ChatCompletionRequest baseRequest, long projectId,
                                     LlmFallbackNotice notice, Long actorId, Long taskId,
                                     McpTaskToolBag mcpBag, Long chatSessionId,
                                     ReActStreamSink sink) {
        return runInternal(baseRequest, projectId, notice, actorId, taskId, mcpBag,
                chatSessionId, sink);
    }

    private ReActOutcome runInternal(ChatCompletionRequest baseRequest, long projectId,
                                     LlmFallbackNotice notice, Long actorId, Long taskId,
                                     McpTaskToolBag mcpBag, Long chatSessionId,
                                     ReActStreamSink sink) {
        // Chat-abort checkpoint at the run head : an abort that
        // landed before this run started must cost zero provider calls.
        if (sink != null && sink.aborted()) {
            return new ReActOutcome(null, baseRequest.messages(), 0, 0, 0, false, null);
        }
        FcPlan plan = functionCalling.assemblePlan(projectId, actorId);
        if (sink != null) {
            // Dialog surface : the built-in aggregate.query
            // tool rides every streamed plan — including the empty-project
            // one — so "how many / how distributed" is answerable without a
            // hand-registered tool row (ExploreBuiltinToolCatalog posture;
            // the run door keeps enforcing every boundary server-side).
            plan = withBuiltInAggregateTool(plan, actorId);
            // package 2: plan.propose rides every streamed plan too —
            // the multi-step card only ever appears on the dialog surface.
            plan = withBuiltInPlanTool(plan);
            // chat.clarify rides every streamed plan as well —
            // the honest-suspension card only ever appears on the dialog.
            plan = withBuiltInClarifyTool(plan);
            // chat.suggest rides every streamed plan too — the
            // proactive-extension chips only ever appear on the dialog.
            plan = withBuiltInSuggestTool(plan);
        }
        if (plan.isEmpty()) {
            LlmChatHandle plain = callLlm(baseRequest, projectId, notice, sink);
            return new ReActOutcome(plain.result(), baseRequest.messages(), 0, 0, 0, false,
                    plain.activeEndpoint());
        }
        if (retrievalTool != null) {
            // AP-1(1) retrieval built-in : rides every plan
            // that actually runs (both surfaces) so a folded pointer can be
            // re-fetched within its owning scope; the empty-plan plain path
            // above stays byte-identical legacy semantics.
            plan = withBuiltInRetrievalTool(plan);
        }
        ChatCompletionRequest decorated = functionCalling.decorate(baseRequest, plan);
        List<ChatMessage> messages = new ArrayList<>(decorated.messages());
        // AP-5 the resident list is MUTABLE per round — search_tools
        // expansions join it, so the next provider round can actually call a
        // just-materialized tool. The pool is the still-deferred candidates;
        // expansion consumes it (no second re-ship of the same definition).
        List<ChatToolDef> activeTools = new ArrayList<>(decorated.tools());
        List<ToolFcAssemblyRemoteItem> catalogPool = new ArrayList<>(plan.deferred());
        int maxRounds = roundPolicy.maxRounds();
        int rounds = 0;
        // counts[0]=dispatched, counts[1]=rejected — one bookkeeping array so
        // the serial route and the fan-out route share a single backfill (the
        // pairing rules must not exist twice).
        int[] counts = new int[2];
        // The fan-out lambda captures the assembly plan; `plan` itself is
        // rebuilt with built-in tools above, so the loop routes read the
        // settled snapshot through this final alias.
        final FcPlan roundPlan = plan;
        // Stall reflection : one guard per run, the K8 bar
        // is read at run start (hot update lands on the NEXT run - the streaks
        // are per-run state, a mid-run switch would mean nothing anyway).
        final StallFingerprintGuard stallGuard = stallPolicy == null ? null
                : new StallFingerprintGuard(stallPolicy.blockAfterAttempts());
        boolean clarifySuspended = false;
        LlmChatHandle handle = null;
        while (true) {
            if (sink != null) {
                drainSteering(sink, messages, rounds);
                // Chat-abort checkpoint at the existing round
                // boundary (same seam as the steering drain): once the
                // user's abort is visible, this loop launches no further
                // provider call and exits without a fabricated answer.
                if (sink.aborted()) {
                    return new ReActOutcome(null, List.copyOf(messages), rounds,
                            counts[0], counts[1], false,
                            handle == null ? null : handle.activeEndpoint());
                }
            }
            handle = callLlm(rebuild(decorated, messages, activeTools), projectId, notice, sink);
            LlmChatResult result = handle.result();
            List<LlmToolCall> calls = result.toolCalls();
            if (calls == null || calls.isEmpty()) {
                // Plain-text finish : before letting the bare
                // answer end the run, consult the dialog's goal-plan door —
                // a returned reminder joins as a system message and the loop
                // keeps going. The door carries its own per-run nudge budget
                // (K8 ai.chat.goal-reconcile-nudges), so the next finish
                // passes once the budget is spent; a nudged round counts
                // against the round cap, so even a runaway door can never
                // loop forever. No door / null reminder = legacy finish.
                ReActFinishReconciler door = sink == null ? null : sink.finishReconciler();
                if (door != null && rounds < maxRounds) {
                    String nudge = null;
                    try {
                        nudge = door.takeFinishNudge(List.copyOf(messages));
                    } catch (RuntimeException doorFault) {
                        log.warn("目标账本对账门异常，本次按原样收口 reason={}",
                                doorFault.getMessage());
                    }
                    if (nudge != null && !nudge.isBlank()) {
                        rounds++;
                        messages.add(new ChatMessage("system", nudge));
                        continue;
                    }
                }
                return new ReActOutcome(result, List.copyOf(messages), rounds,
                        counts[0], counts[1], false, handle.activeEndpoint());
            }
            rounds++;
            messages.add(ChatMessage.assistantWithToolCalls(result.content(), calls));
            int cursor = 0;
            while (cursor < calls.size()) {
                List<LlmToolCall> run = fanout == null ? List.of()
                        : readOnlyRun(fanout, roundPlan, calls, cursor);
                if (run.size() > 1) {
                    // Read-only fan-out (tenet 10): several INDEPENDENT lookups
                    // of one round run at the same time — the loop used to pay
                    // the SUM of their latencies, it now pays the slowest one.
                    // The backfill below still walks the run in the order the
                    // model emitted, so tool_call_id pairing is unchanged.
                    for (LlmToolCall member : run) {
                        if (sink != null) {
                            sink.onToolStart(member.name());
                        }
                    }
                    List<ToolCallOutcome> outcomes = fanout.dispatch(run,
                            member -> guardedDispatch(stallGuard, member,
                                    () -> dispatcher.dispatch(roundPlan, member, actorId,
                                            taskId, mcpBag, null, projectId, chatSessionId)));
                    for (int i = 0; i < run.size(); i++) {
                        backfill(run.get(i), outcomes.get(i), sink, messages, counts);
                    }
                    cursor += run.size();
                    continue;
                }
                LlmToolCall call = calls.get(cursor);
                if (sink != null) {
                    sink.onToolStart(call.name());
                }
                ToolCallOutcome outcome = guardedDispatch(stallGuard, call,
                        () -> AggregateQueryTool.isAggregateCall(call.name())
                        ? aggregateQueryTool.execute(projectId, actorId, call.argumentsJson())
                        : PlanProposeTool.isPlanCall(call.name())
                                ? PlanProposeTool.propose(call.argumentsJson())
                                : ClarifyTool.isClarifyCall(call.name())
                                        ? ClarifyTool.clarify(call.argumentsJson())
                                        : SuggestTool.isSuggestCall(call.name())
                                                ? SuggestTool.suggest(call.argumentsJson())
                                                : retrievalTool != null
                                        && ToolResultRetrievalTool.isRetrievalCall(call.name())
                                        ? retrievalTool.execute(call.argumentsJson(), taskId,
                                                projectId)
                                        : catalog != null
                                                && FcToolCatalog.isSearchCall(call.name())
                                                ? expandFromCatalog(call, catalogPool, activeTools)
                                                : dispatcher.dispatch(roundPlan, call, actorId, taskId,
                                                        mcpBag, null, projectId, chatSessionId));
                backfill(call, outcome, sink, messages, counts);
                if (ClarifyTool.isClarifyCall(call.name()) && outcome.success()) {
                    clarifySuspended = true;
                }
                cursor++;
            }
            if (clarifySuspended) {
                // the clarify card ends the turn on the spot.
                // A second provider call would let the model answer on the
                // user's behalf and burn budget nobody asked for — honest
                // suspension means the transcript waits here (the tool
                // result pair stays in it for replay and the resume turn).
                // invariant 5: before leaving, hand the paused
                // transcript to the door's suspend lane so an open plan the
                // turn proposed reaches the ledger — reload replays the card
                ReActFinishReconciler suspendDoor = sink == null ? null : sink.finishReconciler();
                if (suspendDoor != null) {
                    try {
                        suspendDoor.onRunSuspend(List.copyOf(messages));
                    } catch (RuntimeException doorFault) {
                        log.warn("目标账本挂起通道异常，按原样挂起 reason={}", doorFault.getMessage());
                    }
                }
                return new ReActOutcome(result, List.copyOf(messages), rounds,
                        counts[0], counts[1], false, handle.activeEndpoint());
            }
            if (rounds >= maxRounds) {
                log.warn("ReAct 工具循环达轮次上限（{}={}），停止续跑取末次输出"
                                + "（可能缺终答）taskId={}",
                        FcRoundPolicy.KEY, maxRounds, taskId);
                return new ReActOutcome(result, List.copyOf(messages), rounds,
                        counts[0], counts[1], true, handle.activeEndpoint());
            }
        }
    }

    /**
     * Guard-wrapped tool dispatch shared by BOTH routes (single source, R40):
     * the stall guard is consulted BEFORE the call runs - a blocked shape
     * never reaches the tool, and the strategy-change frame rides back as its
     * refusal content so the model sees it and must switch tactics. A call
     * that does run keeps the streak bookkeeping honest (success clears the
     * shape, failure feeds the fingerprint). Null guard = legacy route.
     */
    private static ToolCallOutcome guardedDispatch(StallFingerprintGuard guard,
                                                   LlmToolCall call,
                                                   java.util.function.Supplier<ToolCallOutcome> route) {
        if (guard == null) {
            return route.get();
        }
        StallVerdict verdict = guard.checkBeforeCall(call.name(), call.argumentsJson());
        if (verdict.blocked()) {
            log.info("stall guard refused same-shape call tool={} streak={}",
                    call.name(), verdict.failureStreak());
            return new ToolCallOutcome(call.name(), false, verdict.guidance());
        }
        ToolCallOutcome outcome = route.get();
        if (outcome.success()) {
            guard.recordSuccess(call.name(), call.argumentsJson());
        } else {
            guard.recordFailure(call.name(), call.argumentsJson(), outcome.content());
        }
        return outcome;
    }

    /**
 * Round-boundary steering drain : every supplement the user
 * injected since the last boundary joins the conversation as a real user
 * message (the provider must see it, not just the browser) and is
 * journaled with the step number that picked it up. Blank payloads are
 * skipped defensively even though the core intake door already refuses
 * them — a journaled empty turn marker would be noise, never steering.
 */
    private static void drainSteering(ReActStreamSink sink, List<ChatMessage> messages,
                                      int rounds) {
        for (String text : sink.takeSteeringTexts()) {
            if (text == null || text.isBlank()) {
                continue;
            }
            messages.add(new ChatMessage("user", text));
            sink.onUserInject(text, rounds + 1);
        }
    }

    /**
     * Maximal run of consecutive read-only fan-out candidates starting at
     * {@code from}. A run of one is reported as such and the caller keeps the
     * serial route — hopping to an executor for a single call buys nothing and
     * would move the tool off the caller thread for no reason.
     */
    private static List<LlmToolCall> readOnlyRun(ReadOnlyToolFanout fanout, FcPlan plan,
                                                 List<LlmToolCall> calls, int from) {
        if (!fanout.isCandidate(plan, calls.get(from))) {
            return List.of(calls.get(from));
        }
        int end = from + 1;
        while (end < calls.size() && fanout.isCandidate(plan, calls.get(end))) {
            end++;
        }
        return calls.subList(from, end);
    }

    /**
     * Shared tool-result backfill for both routes: the trace bracket closes,
     * the {@code role=tool} message joins the transcript under the call's own
     * {@code tool_call_id}, and the run counters move. Assistant tool_calls
     * write-back happens once per round in the caller, so this stays the only
     * place that defines how a result is paired (single source).
     */
    private static void backfill(LlmToolCall call, ToolCallOutcome outcome,
                                 ReActStreamSink sink, List<ChatMessage> messages,
                                 int[] counts) {
        if (sink != null) {
            sink.onToolDone(outcome.toolName(), outcome.success(), outcome.content());
        }
        messages.add(ChatMessage.tool(call.id(), outcome.content()));
        if (outcome.success()) {
            counts[0]++;
        } else {
            counts[1]++;
        }
    }

    /**
     * One provider call: streaming when a sink is present (deltas/reason are
     * forwarded to it), plain otherwise — single branch point, both paths go
     * through the same fallback chain semantics.
     */
    private LlmChatHandle callLlm(ChatCompletionRequest request, long projectId,
                                  LlmFallbackNotice notice, ReActStreamSink sink) {
        // AP-2 single branch point: both blocking and streaming rounds ride the
        // per-round budget fitting (NONE = byte-compatible legacy pass-through).
        // The fit → send → heal-on-vendor-overflow posture is the shared seam
        // LoopContextFitting.send ( S3 single source, also consumed by the
        // generation plane — no second copy of the retry shape).
        java.util.function.Consumer<LlmStreamEvent> relay = event -> {
            if (LlmStreamEvent.KIND_REASON.equals(event.kind())) {
                sink.onReason(event.text());
            } else {
                sink.onDelta(event.text());
            }
        };
        return com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting
                .send(loopFitting, llmFallbackChain, request, projectId, notice,
                        sink == null ? null : relay);
    }

    
    // Constrained decoding (response_format) must survive every per-turn
    // rebuild below: the legacy six-arg copy silently cleared it once and the
    // structured block never reached the endpoint (live window w13b proved it);
    // pinned by ReActRunnerTest.
    private static ChatCompletionRequest rebuild(ChatCompletionRequest decorated,
                                                 List<ChatMessage> messages,
                                                 List<ChatToolDef> activeTools) {
        return new ChatCompletionRequest(decorated.model(), List.copyOf(messages),
                decorated.temperature(), decorated.maxTokens(),
                List.copyOf(activeTools), decorated.toolChoice(),
                null, null, null, decorated.responseFormat());
    }

    /**
 * One in-loop {@code search_tools} call (AP-5 : searches the
 * still-deferred pool, mounts every schema-ready match onto the mutable
 * resident list (deduped by name) and consumes it from the pool — the
 * next rebuild ships the expanded definitions. The refusal text of a
 * miss/blank query rides back as the tool message (fail-soft, never a
 * throw — dispatcher posture shared).
 */
    private ToolCallOutcome expandFromCatalog(LlmToolCall call,
                                              List<ToolFcAssemblyRemoteItem> catalogPool,
                                              List<ChatToolDef> activeTools) {
        FcCatalogSearchOutcome found =
                catalog.execute(call.argumentsJson(), List.copyOf(catalogPool));
        for (ChatToolDef def : found.expandedTools()) {
            String name = def.function().name();
            catalogPool.removeIf(item -> item.name().equals(name));
            boolean alreadyResident = activeTools.stream()
                    .anyMatch(t -> t.function().name().equals(name));
            if (!alreadyResident) {
                activeTools.add(def);
            }
        }
        return new ToolCallOutcome(FcToolCatalog.NAME, found.success(), found.content());
    }

    /**
     * Plan + built-in aggregate tool (dialog-only injection; index untouched
     * — the built-in never routes through the registered-tool dispatcher,
     * ExploreLoopKernel's effectivePlan merge is the same posture).
     */
    private FcPlan withBuiltInAggregateTool(FcPlan plan, Long actorId) {
        List<ChatToolDef> tools = new ArrayList<>(plan.tools());
        tools.add(aggregateQueryTool.toolDef(actorId));
        return new FcPlan(List.copyOf(tools), plan.index(), plan.deferred());
    }

    /**
 * Plan + built-in plan.propose tool (dialog-only injection, pkg 2):
 * the index stays untouched — the built-in never routes through the
 * registered-tool dispatcher (same posture as {@link
 * #withBuiltInAggregateTool}).
 */
    private FcPlan withBuiltInPlanTool(FcPlan plan) {
        List<ChatToolDef> tools = new ArrayList<>(plan.tools());
        tools.add(PlanProposeTool.toolDef());
        return new FcPlan(List.copyOf(tools), plan.index(), plan.deferred());
    }

    /**
 * Plan + built-in chat.clarify tool (dialog-only injection, pkg 1):
 * the same synthetic-surface posture as plan.propose — the definition
 * rides the streamed plan and the loop answers the call locally, so a
 * clarification never touches the registered-tool dispatcher.
 */
    private FcPlan withBuiltInClarifyTool(FcPlan plan) {
        List<ChatToolDef> tools = new ArrayList<>(plan.tools());
        tools.add(ClarifyTool.toolDef());
        return new FcPlan(List.copyOf(tools), plan.index(), plan.deferred());
    }

    /**
 * Plan + built-in chat.suggest tool (dialog-only injection, :
 * the same synthetic-surface posture as chat.clarify — the definition
 * rides the streamed plan and the loop acknowledges the call locally
 * without ending the turn, so a suggestion never touches the
 * registered-tool dispatcher.
 */
    private FcPlan withBuiltInSuggestTool(FcPlan plan) {
        List<ChatToolDef> tools = new ArrayList<>(plan.tools());
        tools.add(SuggestTool.toolDef());
        return new FcPlan(List.copyOf(tools), plan.index(), plan.deferred());
    }

    /**
 * Capability names of the dialog-surface built-ins (single source, R40):
 * they ride every streamed plan and are answered by the loop itself, so
 * they are honest suggestion targets even though they never appear in
 * the registered-tool index. Both the injection ladder above and the
 * delivery gate read THIS list — mounting and allowing can never
 * drift apart.
 */
    public static java.util.Set<String> dialogBuiltinCapabilityNames() {
        return java.util.Set.of(PlanProposeTool.NAME, ClarifyTool.NAME,
                SuggestTool.NAME, AggregateQueryTool.NAME,
                FcToolCatalog.NAME, ToolResultRetrievalTool.NAME);
    }

    /**
 * Mounted intersection for one dialog turn : every
 * capability the user of this actor/project could actually route —
 * the registered candidates of the assembled plan (core-side
 * published∩enabled∩authorization intersection, the exact same assembly
 * the loop would execute against) plus the dialog built-ins. Suggestions
 * naming anything else are dropped server-side before the done frame.
 */
    public java.util.Set<String> allowedCapabilities(long projectId, Long actorId) {
        FcPlan plan = functionCalling.assemblePlan(projectId, actorId);
        if (plan.isEmpty()) {
            // Plain-completion surface: no tool is mounted at all (the
            // built-in ladder below the empty-plan gate never rides), so
            // nothing is an honorable suggestion target.
            return java.util.Set.of();
        }
        java.util.Set<String> allowed = new java.util.HashSet<>(plan.index().keySet());
        plan.deferred().forEach(item -> allowed.add(item.name()));
        allowed.addAll(dialogBuiltinCapabilityNames());
        return java.util.Set.copyOf(allowed);
    }

    /**
 * Plan + built-in retrieval meta-tool (AP-1(1), ③): the
 * index stays untouched — like the other built-ins it never routes
 * through the registered-tool dispatcher (same posture as {@link
 * #withBuiltInAggregateTool}), and ownership of an anchor is enforced by
 * the tool itself before any storage door is asked.
 */
    private FcPlan withBuiltInRetrievalTool(FcPlan plan) {
        List<ChatToolDef> tools = new ArrayList<>(plan.tools());
        tools.add(retrievalTool.toolDef());
        return new FcPlan(List.copyOf(tools), plan.index(), plan.deferred());
    }
}
