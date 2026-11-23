package com.sixbean.testpike.aiservice.doc.domain.component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe provider-usage accumulator of one extraction generation (EE
 * batch ). Fed at the single call seam of the extraction plane, so every
 * summed number equals the booked tp_ai_call_log rows by construction (zero
 * model calls, zero difference reconciliation).
 *
 * <p>Keys are the tp_ai_call_log.function labels the seam already carries —
 * no second label vocabulary. Final-extract buckets run on a bounded pool, so
 * accumulation must survive parallel records.
 */
public final class ExtractFidelityMeter {

    private record Sum(long calls, long prompt, long completion, long reasoning) {
    }

    private static final Sum ZERO = new Sum(0, 0, 0, 0);

    private final Map<String, Sum> byFunction = new ConcurrentHashMap<>();

    /** Book one provider call at the seam (usage as reported by the provider,
     *  never estimated — this face is the ground truth). */
    public void record(String function, int promptTokens, int completionTokens,
                       int reasoningTokens) {
        Sum fresh = new Sum(1, promptTokens, completionTokens, reasoningTokens);
        byFunction.merge(function, fresh, ExtractFidelityMeter::combine);
    }

    private static Sum combine(Sum first, Sum second) {
        return new Sum(first.calls() + second.calls(), first.prompt() + second.prompt(),
                first.completion() + second.completion(), first.reasoning() + second.reasoning());
    }

    public void record(String function, int promptTokens, int completionTokens) {
        record(function, promptTokens, completionTokens, 0);
    }

    /** Booked calls across every plane function (reconciliation face). */
    public int calls() {
        return (int) byFunction.values().stream().mapToLong(Sum::calls).sum();
    }

    public int promptTokensTotal() {
        return (int) byFunction.values().stream().mapToLong(Sum::prompt).sum();
    }

    public int completionTokensTotal() {
        return (int) byFunction.values().stream().mapToLong(Sum::completion).sum();
    }

    public int callsOf(String function) {
        return (int) byFunction.getOrDefault(function, ZERO).calls();
    }

    public int promptOf(String function) {
        return (int) byFunction.getOrDefault(function, ZERO).prompt();
    }

    public int completionOf(String function) {
        return (int) byFunction.getOrDefault(function, ZERO).completion();
    }

    public int reasoningOf(String function) {
        return (int) byFunction.getOrDefault(function, ZERO).reasoning();
    }

    /**
 * Ticket 1 (reuse-scoped fidelity): source material this generation did
 * not re-read because a reuse face (the bucket-result cache or the
 * per-generation item cache) answered the plane from an earlier,
 * fully-booked generation. Booked at the cache-hit site with the same
 * estimator face as the denominator, so the fidelity row can answer
 * read-OR-reused coverage instead of penalising the zero-call leg.
 */
    private final java.util.concurrent.atomic.AtomicInteger reusedSourceTokens =
            new java.util.concurrent.atomic.AtomicInteger();

    public void bookReusedSourceTokens(int tokens) {
        if (tokens > 0) {
            reusedSourceTokens.addAndGet(tokens);
        }
    }

    public int reusedSourceTokens() {
        return reusedSourceTokens.get();
    }
}
