package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmAuditRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.log.LogCollapseWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Component
public class LlmFallbackChain {

    private static final Logger log = LoggerFactory.getLogger(LlmFallbackChain.class);

    /** Per-provider error truncation bound in aggregated failure details . */
    private static final int PROVIDER_ERROR_DETAIL_MAX_CHARS = 200;

    /** provider-failure family collapse window key (hot-tunable, 60s fallback). */
    static final String COLLAPSE_PROPERTY = "tp.log.llm-provider-fail-collapse-window-ms";
    /** Rollover ledger pointer: every degradation occurrence is audited through
     *  CoreGateway.writeAudit, whose records surface on the llm_fallback audit
     *  page (R53 — collapsing never removes the authoritative record). */
    private static final String PROVIDER_LEDGER_NOTE = "明细见大模型降级审计与任务错误记录";

    /** Shared default window: property-driven, re-resolved per occurrence (static like the family itself). */
    private static volatile LogCollapseWindow collapseWindow = new LogCollapseWindow(COLLAPSE_PROPERTY);

    /** WARN sink (package-seam for the L2 collapse suites; production = class logger). */
    Logger warnSink = log;

    /**
     * Test seam for the shared provider-failure window ({@code null} restores
     * the property-driven default). Suite runs must reset it in @AfterEach —
     * the field exists so suites can pin a fake-clock window without a second
     * constructor chain (every caller keeps using the @Autowired full constructor).
     */
    static void useCollapseWindow(LogCollapseWindow window) {
        collapseWindow = window != null ? window : new LogCollapseWindow(COLLAPSE_PROPERTY);
    }

    /** Constructor-injection variant used by the collapse suites. */
    LlmFallbackChain(LlmProviderFactory factory, ProjectLlmRouter router,
                     CoreGateway coreGateway, LlmRetryPolicy retryPolicy,
                     LlmTimeoutResolver timeoutResolver, PromptSizeGuard guard,
                     UsageCalibrationRecorder recorder, Logger warnSink) {
        this(factory, router, coreGateway, retryPolicy, timeoutResolver, guard, recorder);
        if (warnSink != null) {
            this.warnSink = warnSink;
        }
    }

    private final LlmProviderFactory factory;
    private final ProjectLlmRouter router;
    private final CoreGateway coreGateway;
    private final LlmRetryPolicy retryPolicy;
    private final LlmTimeoutResolver timeoutResolver;
 
    private final PromptSizeGuard promptSizeGuard;
 
    private final UsageCalibrationRecorder calibrationRecorder;

    /**
 * DD batch the extraction plane books its generation rows at
 * this door through the shared {@link AiCallLogWriter} — the writer, the
 * current generation token and the row label are pushed in by the doc plane
 * (setter wiring, not constructor churn: every other caller keeps the plain
 * door). Null writer = this chain books nothing itself (byte-identical
 * legacy for chat/other planes, which keep their own per-call rows).
 */
    private volatile AiCallLogWriter callLedgerWriter;
    private volatile java.util.function.Supplier<String> callLedgerToken;
    private volatile String callLedgerFunction;
    private volatile java.util.function.UnaryOperator<java.time.Duration> callLedgerBudget;

    /** Wire the generation-token ledger face (doc plane only; null token = rows
     *  land with a null token column, which is exactly the non-extraction face;
     *  null budget seam = the plain timeoutResolver face, byte-identical). */
    public void enableCallLedger(AiCallLogWriter writer,
                                 java.util.function.Supplier<String> tokenSupplier,
                                 String functionLabel) {
        enableCallLedger(writer, tokenSupplier, functionLabel, null);
    }

    /** Same + the per-attempt budget seam: the extraction plane hands its
 * deadline clamp through so a spent window hard-stops provider attempts
 * even inside the chain loop . */
    public void enableCallLedger(AiCallLogWriter writer,
                                 java.util.function.Supplier<String> tokenSupplier,
                                 String functionLabel,
                                 java.util.function.UnaryOperator<java.time.Duration> budgetSeam) {
        this.callLedgerWriter = writer;
        this.callLedgerToken = tokenSupplier;
        this.callLedgerFunction = functionLabel;
        this.callLedgerBudget = budgetSeam;
    }

    /** Read the live generation token defensively (absent supplier or a broken
     *  lookup never changes the provider call itself). */
    private String currentLedgerToken() {
        java.util.function.Supplier<String> supplier = callLedgerToken;
        if (supplier == null) {
            return null;
        }
        try {
            return supplier.get();
        } catch (Exception e) {
            return null;
        }
    }

    /** One tp_ai_call_log row per provider call at the shared door. Success
     *  rows carry the endpoint identity and reported usage; failure rows land
     *  with zero tokens honestly (unknown cost), the measured elapsed time and
     *  the error code — the burn happened and must be reconcilable per
     *  generation. A writer outage is swallowed: observability never breaks
     *  the call (same posture as the writer itself). */
    private void persistLedgerRow(LlmEndpointConfig endpoint, LlmChatResult result,
                                  long projectId, long latencyMs, String errorCode) {
        AiCallLogWriter writer = callLedgerWriter;
        if (writer == null || endpoint == null) {
            return;
        }
        try {
            writer.insert(projectId,
                    result != null && result.provider() != null && !result.provider().isBlank()
                            ? result.provider() : endpoint.provider(),
                    result != null && result.model() != null && !result.model().isBlank()
                            ? result.model() : endpoint.modelName(),
                    callLedgerFunction == null ? "chat" : callLedgerFunction,
                    result == null ? 0 : result.promptTokens(),
                    result == null ? 0 : result.completionTokens(),
                    latencyMs, currentLedgerToken(), errorCode);
        } catch (Exception e) {
            log.warn("AI 调用留痕写入失败 projectId={} provider={} err={}（观测面不阻断）",
                    projectId, endpoint.provider(), e.getMessage());
        }
    }


    public LlmFallbackChain(LlmProviderFactory factory, ProjectLlmRouter router,
                            CoreGateway coreGateway, LlmRetryPolicy retryPolicy,
                            LlmTimeoutResolver timeoutResolver) {
        this(factory, router, coreGateway, retryPolicy, timeoutResolver, null, null);
    }

    
    @Autowired
    public LlmFallbackChain(LlmProviderFactory factory, ProjectLlmRouter router,
                            CoreGateway coreGateway, LlmRetryPolicy retryPolicy,
                            LlmTimeoutResolver timeoutResolver, PromptSizeGuard guard,
                            UsageCalibrationRecorder recorder) {
        this.factory = factory;
        this.router = router;
        this.coreGateway = coreGateway;
        this.retryPolicy = retryPolicy;
        this.timeoutResolver = timeoutResolver;
        this.promptSizeGuard = guard;
        this.calibrationRecorder = recorder;
    }

    
    public LlmFallbackChain(LlmProviderFactory factory, ProjectLlmRouter router,
                            CoreGateway coreGateway, LlmRetryPolicy retryPolicy) {
        this(factory, router, coreGateway, retryPolicy,
                new LlmTimeoutResolver(coreGateway, new LlmProperties()));
    }

    
    public LlmChatResult chat(ChatCompletionRequest request, long projectId,
                              LlmFallbackNotice notice) {
        return chatHandle(request, projectId, notice, false).result();
    }

 
    public LlmChatResult chat(ChatCompletionRequest request, long projectId,
                              LlmFallbackNotice notice, boolean modelPinned) {
        return chatHandle(request, projectId, notice, modelPinned).result();
    }

    /**
 * Task-level deadline overload : the caller supplies a clamp
 * that shortens each per-call timeout to the remaining task window (and
 * fails fast once the window is spent). Other callers keep the 4-arg path —
 * null clamp = legacy semantics, zero behavior change.
 */
    public LlmChatResult chat(ChatCompletionRequest request, long projectId,
                              LlmFallbackNotice notice, boolean modelPinned,
                              java.util.function.UnaryOperator<java.time.Duration> timeoutClamp) {
        return chatHandle(request, projectId, notice, modelPinned, timeoutClamp).result();
    }

    
    public LlmChatHandle chatHandle(ChatCompletionRequest request, long projectId,
                                    LlmFallbackNotice notice) {
        return chatHandle(request, projectId, notice, false);
    }

    
    public LlmChatHandle chatHandle(ChatCompletionRequest request, long projectId,
                                    LlmFallbackNotice notice, boolean modelPinned) {
        return chatHandle(request, projectId, notice, modelPinned, null);
    }

    /**
 * Same + optional task-level timeout clamp (AP batch , hardened by DD
 * batch): the clamp re-applies right before every attempt AND every retry,
 * shortening each per-call timeout to the remaining window; once the window
 * is spent the retry loop hard-stops with AI_TIMEOUT (no backoff burn, no
 * standby-endpoint calls). null clamp = legacy behavior for every other caller.
 */
    public LlmChatHandle chatHandle(ChatCompletionRequest request, long projectId,
                                    LlmFallbackNotice notice, boolean modelPinned,
                                    java.util.function.UnaryOperator<java.time.Duration> timeoutClamp) {
        return handle(request, projectId, notice, modelPinned, timeoutClamp,
                new LlmEndpointCall() {
                    @Override
                    public LlmChatResult defaultTimeout(ChatCompletionRequest req,
                                                        LlmEndpointConfig endpoint) {
                        return factory.chatFor(req, endpoint);
                    }

                    @Override
                    public LlmChatResult withTimeout(ChatCompletionRequest req,
                                                     LlmEndpointConfig endpoint, Duration timeout) {
                        return factory.chatFor(req, endpoint, timeout);
                    }
                });
    }

    /**
 * Streaming entry of the fallback chain (AO batch dialog kernel, segment
 * 2b): identical route/retry/degrade/fail-closed semantics as
 * {@link #chatHandle}, but every provider delta reaches {@code onEvent} as
 * it arrives so the orchestrator can emit SSE frames live. A failed
 * endpoint's partial deltas stay delivered-up-to-there (the honest-partial
 * posture of the transport), and degradation to the next provider keeps
 * the E7 audit/notification — one loop, one source, no streaming fork.
 */
    public LlmChatHandle chatStreamHandle(ChatCompletionRequest request, long projectId,
                                          LlmFallbackNotice notice,
                                          java.util.function.Consumer<LlmStreamEvent> onEvent) {
        return chatStreamHandle(request, projectId, notice, onEvent, null);
    }

    /**
 * Same streaming entry + optional task-level timeout clamp (DD batch ,
 * mirroring the 5-arg blocking {@link #chat} form): the clamp re-applies
 * before every attempt and retry and hard-stops a spent window, so a
 * streaming extraction call stays inside its task budget. null clamp =
 * legacy streaming behavior for the dialog plane (zero drift).
 */
    public LlmChatHandle chatStreamHandle(ChatCompletionRequest request, long projectId,
                                          LlmFallbackNotice notice,
                                          java.util.function.Consumer<LlmStreamEvent> onEvent,
                                          java.util.function.UnaryOperator<java.time.Duration> timeoutClamp) {
        return handle(request, projectId, notice, false, timeoutClamp, streamingCall(onEvent));
    }

    /** Streaming endpoint call shared by both chatStreamHandle entries (R40: one loop body). */
    private LlmEndpointCall streamingCall(java.util.function.Consumer<LlmStreamEvent> onEvent) {
        return new LlmEndpointCall() {
            @Override
            public LlmChatResult defaultTimeout(ChatCompletionRequest req,
                                                LlmEndpointConfig endpoint) {
                return factory.chatStreamFor(req, endpoint, onEvent);
            }

            @Override
            public LlmChatResult withTimeout(ChatCompletionRequest req, LlmEndpointConfig endpoint,
                                             Duration timeout) {
                // idle bound resolved per attempt = K8 hot update takes effect next call
                return factory.chatStreamFor(req, endpoint, timeout,
                        timeoutResolver.resolveStreamIdle(), onEvent);
            }
        };
    }

    /** Shared fallback loop; {@code call} decides streaming vs blocking per endpoint. */
    private LlmChatHandle handle(ChatCompletionRequest request, long projectId,
                                 LlmFallbackNotice notice, boolean modelPinned,
                                 java.util.function.UnaryOperator<java.time.Duration> timeoutClamp,
                                 LlmEndpointCall call) {
        if (factory.isMock()) {
            LlmEndpointConfig mockEndpoint = router.route(projectId);
            ChatCompletionRequest mockRequest =
                    withProjectCacheHint(request, mockEndpoint, projectId);
            long mockStartedAt = System.currentTimeMillis();
            LlmChatResult mockResult = call.defaultTimeout(mockRequest, mockEndpoint);
            recordCalibration(mockRequest, mockResult, mockEndpoint, projectId);
            persistLedgerRow(mockEndpoint, mockResult, projectId,
                    System.currentTimeMillis() - mockStartedAt, null);
            return new LlmChatHandle(mockResult, mockEndpoint);
        }
        List<LlmEndpointConfig> chain = router.routeChain(projectId);
        if (chain.isEmpty()) {
            throw new ServiceException("未配置可用 LLM 供应商：请在系统设置-大模型清单启用至少一个端点",
                    BizErrorCode.AI_PROVIDER_ERROR);
        }
        List<Map<String, Object>> failedEndpoints = new ArrayList<>();
        ServiceException last = null;
        boolean primaryEndpoint = true;
        for (LlmEndpointConfig endpoint : chain) {
            long startedAt = System.currentTimeMillis();
            // Degrade rebuild goes through the whole-shape withModel copy: any field
            // carried on the request (FC tools, response_format, stream options,
            // cache key) now survives to the standby endpoint by construction.
            ChatCompletionRequest endpointRequest = withProjectCacheHint(
                    (primaryEndpoint || modelPinned) ? request
                            : request.withModel(endpoint.modelName()),
                    endpoint, projectId);
            primaryEndpoint = false;
            if (promptSizeGuard != null) {
                try {
                    promptSizeGuard.check(endpointRequest.messages(), Integer.MAX_VALUE, true,
                            endpoint.promptLimitTokens(),
                            TokenEstimatorRegistry.resolve(endpoint.tokenizerFamily(),
                                    endpoint.modelName()));
                } catch (ServiceException limitEx) {
                    last = limitEx;
                    failedEndpoints.add(endpointSnapshot(endpoint, limitEx));
                    log.warn("PromptSizeGuard 拦截 projectId={} provider={} limit={} err={}",
                            projectId, endpoint.provider(), endpoint.promptLimitTokens(),
                            limitEx.getMessage());
                    continue;
                }
            }
            // Task-level clamp re-applies on EVERY retry attempt (DD hard stop):
            // per-attempt timeout = min(resolved K8 timeout, window remaining), and
            // the attempt gate runs outside the retryable path, so a spent window
            // aborts the loop immediately instead of burning backoff retries (or
            // standby endpoints) on a dead budget. null clamp = identity, i.e. the
            // legacy once-per-endpoint timeout with zero behavior change.
            final java.time.Duration baseTimeout = timeoutResolver.resolve();
            final java.util.function.UnaryOperator<java.time.Duration> budget =
                    timeoutClamp == null ? t -> t : timeoutClamp;
            // Ledger budget face : the plane's window seam is asked
            // per attempt too, so standby/degrade retries inherit the hard stop;
            // unwired door = identity, every other caller byte-identical.
            final java.util.function.UnaryOperator<java.time.Duration> ledgerBudget =
                    callLedgerBudget;
            // Task-budget stop marker (exception identity, no subclassing): set
            // when either budget seam reports the window spent while this
            // endpoint is attempted. The degrade catch rethrows that stop
            // verbatim instead of laundering it as a provider failure — an
            // endpoint never attempted must not appear in the aggregated
            // llm_fallback detail, and its phantom ledger row is never booked
            // (live incident: a spent-window stop surfaced as an aggregated
            // provider-failure list with the never-attempted standby
            // endpoint mixed in).
            final java.util.concurrent.atomic.AtomicReference<ServiceException>
                    budgetStop = new java.util.concurrent.atomic.AtomicReference<>();
            // One composed per-attempt face (R40: applied exactly once per
            // attempt, in this single place — the seam contract pinned by the
            // ledger suite): the ledger seam clamps first, the per-call clamp
            // second, and the COMPOSED value is what the provider call is
            // actually granted — probing it only for null and discarding the
            // clamped number let a nearly-spent window still burn a
            // full-length K8-timeout call (live incident: 155-378s provider
            // rows landing after the +5400s budget expired). Both spent
            // shapes abort: a null return keeps the door-level message, a
            // throw keeps the seam's own budget message. Because the gate
            // runs before the retryable try on every attempt, a spent window
            // aborts endpoint attempts without burning a backoff or a call.
            final java.util.function.UnaryOperator<java.time.Duration> attemptFace =
                    timeout -> {
                        try {
                            java.time.Duration stepped = ledgerBudget == null
                                    ? timeout : ledgerBudget.apply(timeout);
                            if (stepped == null) {
                                throw new ServiceException(
                                        "任务时间窗口已用尽，提取链未再发起供应商调用",
                                        BizErrorCode.AI_TIMEOUT);
                            }
                            return budget.apply(stepped);
                        } catch (ServiceException spent) {
                            budgetStop.compareAndSet(null, spent);
                            throw spent;
                        }
                    };
            // Gate result consumed by the attempt supplier (one seam question
            // per attempt: gate sets, supplier grants).
            final java.util.concurrent.atomic.AtomicReference<java.time.Duration>
                    attemptTimeout = new java.util.concurrent.atomic.AtomicReference<>();
            try {
                LlmChatResult result = retryPolicy.execute(
                        () -> call.withTimeout(endpointRequest, endpoint,
                                attemptTimeout.get()),
                        () -> attemptTimeout.set(attemptFace.apply(baseTimeout)));
                persistLedgerRow(endpoint, result, projectId,
                        System.currentTimeMillis() - startedAt, null);
                if (!failedEndpoints.isEmpty()) {
                    publishFallback(projectId, notice, failedEndpoints, endpoint,
                            System.currentTimeMillis() - startedAt);
                }
                recordCalibration(endpointRequest, result, endpoint, projectId);
                return new LlmChatHandle(result, endpoint);
            } catch (ServiceException e) {
                ServiceException spent = budgetStop.get();
                if (spent != null) {
                    throw spent; // budget stop ≠ provider failure
                }
                persistLedgerRow(endpoint, null, projectId,
                        System.currentTimeMillis() - startedAt,
                        String.valueOf(e.getCode()));
                last = e;
                failedEndpoints.add(endpointSnapshot(endpoint, e));
                // single-sourced collapse window (R40) — the same
                // provider+error fingerprint inside the window emits one line;
                // the rollover line carries the count plus the audit pointer,
                // and the per-provider detail stays in the llm_fallback audit
                // written below (R53, never silently swallowed).
                LlmDegradationLogCollapse.admitAndLog(collapseWindow, warnSink, "llm-provider-fail",
                        PROVIDER_LEDGER_NOTE,
                        "LLM 供应商失败 projectId={} provider={} err={}（尝试降级下一供应商）",
                        projectId, endpoint.provider(), e.getMessage());
            }
        }
        // Exhausted-chain aggregation : the dead-letter failure classifier
        // matches transport tokens (connection refused / I/O error) that only the
        // FIRST failed endpoint produces. Rethrowing the last provider's text alone
        // hid that signal behind, e.g., a rate-limit message, so the ledger booked
        // UNKNOWN forever. Aggregate every provider's own reason when more than one
        // endpoint was tried; a single-candidate chain keeps the byte-identical
        // legacy message (UI text zero drift). Error code stays the last provider's.
        if (failedEndpoints.size() < 2) {
            throw last;
        }
        throw new ServiceException(
                last.getMessage() + "；全链失败明细：" + formatProviderFailureDetails(failedEndpoints),
                last.getCode());
    }

    /**
     * Single-source rendering (R40) of per-provider failure details, shared by
     * the fallback notification and the exhausted-chain aggregation above. Each
     * provider's error is truncated so one oversized upstream payload cannot
     * drown out the other providers' signals.
     */
    private static String formatProviderFailureDetails(List<Map<String, Object>> failedEndpoints) {
        StringBuilder text = new StringBuilder();
        for (Map<String, Object> endpoint : failedEndpoints) {
            if (text.length() > 0) {
                text.append('；');
            }
            String error = String.valueOf(endpoint.get("error"));
            if (error.length() > PROVIDER_ERROR_DETAIL_MAX_CHARS) {
                error = error.substring(0, PROVIDER_ERROR_DETAIL_MAX_CHARS) + "…";
            }
            text.append(endpoint.get("provider")).append('（').append(error).append('）');
        }
        return text.toString();
    }

 /**
 * Prompt-cache hint attach point : the only place that decides
 * whether an outbound request carries {@code prompt_cache_key}. Attached only for
 * multi-turn calls (more than two messages) on cache-hint-enabled models, with a
 * stable PII-free project routing key. One loop, one source — every candidate
 * endpoint of a degradation chain gets the identical treatment; disabled models
 * and single-turn calls keep legacy request bodies (NON_NULL serializes nothing).
 */
 private static ChatCompletionRequest withProjectCacheHint(ChatCompletionRequest request,
                                                           LlmEndpointConfig endpoint,
                                                           long projectId) {
     if (!endpoint.cacheHintEnabled() || request.messages() == null
             || request.messages().size() <= 2) {
         return request;
     }
     return request.withPromptCacheKey("tp-project-" + projectId);
 }

 
    private void recordCalibration(ChatCompletionRequest request, LlmChatResult result,
                                   LlmEndpointConfig endpoint, long projectId) {
        if (calibrationRecorder == null || result == null) {
            return;
        }
        try {
            calibrationRecorder.record(request, result, endpoint, projectId);
        } catch (Exception e) {
            log.warn("usage 校准采样异常（不阻断主链）: {}", e.getMessage());
        }
    }

    
    private void publishFallback(long projectId, LlmFallbackNotice notice,
                                 List<Map<String, Object>> failedEndpoints,
                                 LlmEndpointConfig target, long elapsedMs) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("failedProviders", failedEndpoints);
        before.put("retryLimit", retryPolicy.getMaxAttempts());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("activeProvider", target.provider());
        after.put("activeBaseUrl", target.baseUrl());
        after.put("activeSource", target.source());
        after.put("elapsedMs", elapsedMs);
        coreGateway.writeAudit(new LlmAuditRequest(
                notice == null ? null : notice.userId(), projectId,
                "llm_fallback", notice == null ? null : notice.title(),
                "LLM_FALLBACK", before, after));
        if (notice != null && notice.userId() != null) {
            // Same single-source rendering as the exhausted-chain aggregation (R40).
            String reason = formatProviderFailureDetails(failedEndpoints);
            coreGateway.createNotification(notice.userId(), notice.projectId(),
                    "AI_LLM_FALLBACK", "AI 服务已自动切换供应商",
                    notice.title() + " 调用供应商失败：" + reason
                            + "；系统已自动切换到 " + target.provider() + " 继续完成，无需处理。",
                    "llm-fallback:" + projectId + ":" + target.provider());
        }
        log.info("LLM 降级 projectId={} from={} to={}", projectId,
                failedEndpoints.stream().map(l -> String.valueOf(l.get("provider"))).toList(),
                target.provider());
    }

 
    private Map<String, Object> endpointSnapshot(LlmEndpointConfig endpoint, ServiceException e) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("provider", endpoint.provider());
        snapshot.put("baseUrl", endpoint.baseUrl());
        snapshot.put("error", e.getMessage());
        snapshot.put("code", e.getCode());
        return snapshot;
    }
}
