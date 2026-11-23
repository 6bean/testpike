package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.log.LogCollapseWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;


public class LlmRetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(LlmRetryPolicy.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** the retry-exhausted family runs through the shared
 * collapse window (hot-tunable property key, 60s fallback; <=0 disables). */
    static final String COLLAPSE_PROPERTY = "tp.log.llm-retry-collapse-window-ms";
    /** Rollover ledger pointer: the exhausted detail still reaches the caller
     *  through the thrown exception, whose record lives in the task error log
     *  and the dead-letter ledger page (R53 — never silently swallowed). */
    private static final String RETRY_LEDGER_NOTE = "明细以任务错误记录与死信台账页为账权威";

    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final LogCollapseWindow collapseWindow;
    private final Logger warnSink;

    public LlmRetryPolicy(int maxAttempts, long baseDelayMs, long maxDelayMs) {
        this(maxAttempts, baseDelayMs, maxDelayMs, null, null);
    }

    /**
 * full constructor: collapse window and WARN sink are
 * injectable (L2 collapse suites pin a fake-clock window and a capturing
 * logger); null = production defaults = hot-tunable property window and
 * the class logger.
 */
    LlmRetryPolicy(int maxAttempts, long baseDelayMs, long maxDelayMs,
                   LogCollapseWindow collapseWindow, Logger warnSink) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.collapseWindow = collapseWindow != null ? collapseWindow : defaultCollapseWindow();
        this.warnSink = warnSink != null ? warnSink : log;
    }

    /** Production default window: property-driven, re-resolved on every check. */
    private static LogCollapseWindow defaultCollapseWindow() {
        return new LogCollapseWindow(COLLAPSE_PROPERTY);
    }

    /** Effective window (package-private: suites assert the key/fallback contract). */
    LogCollapseWindow collapseWindow() {
        return collapseWindow;
    }

    
    public static LlmRetryPolicy defaults() {
        return new LlmRetryPolicy(3, 500, 5000);
    }

    
    public int getMaxAttempts() {
        return maxAttempts;
    }

    
    public LlmChatResult execute(Supplier<LlmChatResult> call) {
        return executeGeneric(call);
    }

    /** Retry with the pre-attempt budget gate (see {@link #executeGeneric(Supplier, Runnable)}). */
    public LlmChatResult execute(Supplier<LlmChatResult> call, Runnable attemptGate) {
        return executeGeneric(call, attemptGate);
    }

    
    public <T> T executeGeneric(Supplier<T> call) {
        return executeGeneric(call, null);
    }

    /**
     * Same retry loop with a pre-attempt budget gate (DD extraction hard stop):
     * the gate runs BEFORE the retryable try on every attempt, so a thrown
     * gate failure (task time budget spent) aborts immediately — no backoff
     * burn, no further supplier call, and the original exception surfaces
     * unwrapped. Null gate = legacy behavior, byte-identical loop.
     */
    public <T> T executeGeneric(Supplier<T> call, Runnable attemptGate) {
        List<String> timeline = new ArrayList<>();
        ServiceException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attemptGate != null) {
                attemptGate.run();
            }
            try {
                return call.get();
            } catch (ServiceException e) {
                last = e;
                timeline.add("第" + attempt + "次(" + LocalTime.now().format(TS) + ") "
                        + e.getMessage());
                if (!isRetryable(e.getCode())) {
                    throw e;
                }
                if (attempt < maxAttempts) {
                    sleep(Math.min(baseDelayMs << (attempt - 1), maxDelayMs));
                }
            }
        }
        // single-sourced collapse window (R40) — repeats of the
        // same fingerprint inside the window emit one line; the rollover line
        // carries the count plus the task-error-ledger pointer (R53).
        LlmDegradationLogCollapse.admitAndLog(collapseWindow, warnSink, "llm-retry-exhausted",
                RETRY_LEDGER_NOTE, "LLM 调用重试耗尽（{} 次）：{}", maxAttempts, timeline);
        throw new ServiceException(last.getMessage() + "；重试时间线：" + String.join(" → ", timeline),
                last.getCode());
    }

    private static boolean isRetryable(Integer code) {
        return code != null && (code == BizErrorCode.AI_TIMEOUT
                || code == BizErrorCode.AI_PROVIDER_ERROR);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
