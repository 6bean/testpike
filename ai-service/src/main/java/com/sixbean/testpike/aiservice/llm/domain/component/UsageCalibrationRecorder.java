package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.common.util.LlmExtractMarkers;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;


@Component
public class UsageCalibrationRecorder {

    private static final Logger log = LoggerFactory.getLogger(UsageCalibrationRecorder.class);

    
    static final int QUEUE_CAPACITY = 256;

    private final CoreGateway coreGateway;
    private final TokenCounter tokenCounter;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong cacheLedgerPrompt = new AtomicLong();
    private final AtomicLong cacheLedgerCached = new AtomicLong();
    private final ThreadPoolExecutor executor;

    public UsageCalibrationRecorder(CoreGateway coreGateway, TokenCounter tokenCounter) {
        this.coreGateway = coreGateway;
        this.tokenCounter = tokenCounter;
        this.executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "usage-calibration");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    
    public void record(ChatCompletionRequest request, LlmChatResult result,
                       LlmEndpointConfig endpoint, long projectId) {
        CalibrationSample sample = build(request, result, endpoint, projectId);
        feedCacheLedger(result);
        try {
            executor.execute(() -> {
                try {
                    coreGateway.recordLlmUsage(sample);
                } catch (Exception e) {
                    log.warn("usage 校准采样写失败（观测面不阻断）: {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            long total = dropped.incrementAndGet();
            log.warn("usage 校准队列满/拒绝，丢弃采样（累计 {}）: {}", total, e.getMessage());
        }
    }

 
    CalibrationSample build(ChatCompletionRequest request, LlmChatResult result,
                            LlmEndpointConfig endpoint, long projectId) {
        TokenEstimator estimator = endpoint == null
                ? tokenCounter
                : TokenEstimatorRegistry.resolve(endpoint.tokenizerFamily(), endpoint.modelName());
        int est = CalibrationSample.estimateOf(estimator, request.messages());
        String stage = request.messages().stream()
                .anyMatch(m -> "system".equals(m.role())
                        && m.textContent().contains(LlmExtractMarkers.DOC_EXTRACT_SUMMARY))
                ? CalibrationSample.CHAIN_SUMMARY : CalibrationSample.CHAIN_EXTRACT;
        return new CalibrationSample(
                endpoint == null ? null : endpoint.llmConfigId(),
                projectId,
                result.provider(),
                result.model(),
                stage,
                est,
                result.promptTokens(),
                result.completionTokens(),
                result.latencyMs(),
                estimator.engineId(),
                result.cachedTokens());
    }

    /** Feed the rolling prefix-cache ledger and print one per-call
     * measurement line — hit ratio is a managed target, so each sampled
     * call states its own and the accumulated numbers in one readable row. */
    private void feedCacheLedger(LlmChatResult result) {
        long prompt = result.promptTokens();
        if (prompt <= 0) {
            return;
        }
        long cached = Math.max(0, result.cachedTokens());
        cacheLedgerPrompt.addAndGet(prompt);
        cacheLedgerCached.addAndGet(cached);
        log.info("prompt-cache 经济性: provider={} model={} 本次 cached={}/prompt={} ({}%) 累计命中率={}%",
                result.provider(), result.model(), cached, prompt,
                Math.round(100.0 * cached / prompt),
                Math.round(100.0 * prefixCacheHitRatio()));
    }

    
    public long droppedCount() {
        return dropped.get();
    }

    /** Rolling prompt-token total reported by providers (prefix-cache ledger
     * denominator; samples without usage never inflate it). */
    public long reportedPromptTokens() {
        return cacheLedgerPrompt.get();
    }

    /** Rolling total of prompt tokens served from the provider prefix cache
     * (the numerator the hit ratio is computed from). */
    public long cachedPrefixTokens() {
        return cacheLedgerCached.get();
    }

    /** Managed-target view of prefix economics: cached / reported prompt
     * tokens over every sampled call so far; honest 0 before any usage. */
    public double prefixCacheHitRatio() {
        long prompt = cacheLedgerPrompt.get();
        return prompt == 0 ? 0.0 : (double) cacheLedgerCached.get() / prompt;
    }

    
    public static UsageCalibrationRecorder from(ObjectProvider<UsageCalibrationRecorder> provider) {
        return provider.getIfAvailable();
    }
}
