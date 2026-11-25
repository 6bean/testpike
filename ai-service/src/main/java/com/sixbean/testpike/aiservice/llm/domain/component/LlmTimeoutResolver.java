package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;


public class LlmTimeoutResolver {

    private static final Logger log = LoggerFactory.getLogger(LlmTimeoutResolver.class);

    
    static final int MIN_SECONDS = 10;
    static final int MAX_SECONDS = 900;

    /** Allowed range for the streaming inter-line idle bound (5~300s; the core
     *  save side pins the same range as a second guard). */
    static final int STREAM_IDLE_MIN_SECONDS = 5;
    static final int STREAM_IDLE_MAX_SECONDS = 300;

    private final CoreGateway coreGateway;
    private final LlmProperties properties;

    public LlmTimeoutResolver(CoreGateway coreGateway, LlmProperties properties) {
        this.coreGateway = coreGateway;
        this.properties = properties;
    }

    
    public Duration resolve() {
        String raw = coreGateway == null ? null : coreGateway.getSysConfig("llm.timeout-seconds");
        if (raw != null) {
            try {
                int seconds = Integer.parseInt(raw.trim());
                if (seconds >= MIN_SECONDS && seconds <= MAX_SECONDS) {
                    return Duration.ofSeconds(seconds);
                }
                log.warn("K8 llm.timeout-seconds={} out of range (allowed {}-{}), falling back to config file value {}s",
                        raw, MIN_SECONDS, MAX_SECONDS, properties.getTimeoutSeconds());
            } catch (NumberFormatException e) {
                log.warn("K8 llm.timeout-seconds={} is not a valid integer, falling back to config file value {}s",
                        raw, properties.getTimeoutSeconds());
            }
        }
        return Duration.ofSeconds(properties.getTimeoutSeconds());
    }

    /**
     * Resolve the current streaming inter-line idle bound (K8 key
     * ai.llm.stream-idle-timeout-seconds → install fallback). Same hot-update
     * channel as {@link #resolve()}: core's write path refreshes the cache, so a
     * saved value takes effect on the next call. Invalid or out-of-range values
     * degrade to the config-file default with a warn — the guard is never
     * silently off.
     */
    public Duration resolveStreamIdle() {
        String raw = coreGateway == null ? null
                : coreGateway.getSysConfig("ai.llm.stream-idle-timeout-seconds");
        if (raw != null) {
            try {
                int seconds = Integer.parseInt(raw.trim());
                if (seconds >= STREAM_IDLE_MIN_SECONDS && seconds <= STREAM_IDLE_MAX_SECONDS) {
                    return Duration.ofSeconds(seconds);
                }
                log.warn("K8 ai.llm.stream-idle-timeout-seconds={} out of range (allowed {}-{}), falling back to config file value {}s",
                        raw, STREAM_IDLE_MIN_SECONDS, STREAM_IDLE_MAX_SECONDS,
                        properties.getStreamIdleTimeoutSeconds());
            } catch (NumberFormatException e) {
                log.warn("K8 ai.llm.stream-idle-timeout-seconds={} is not a valid integer, falling back to config file value {}s",
                        raw, properties.getStreamIdleTimeoutSeconds());
            }
        }
        return Duration.ofSeconds(properties.getStreamIdleTimeoutSeconds());
    }

    /**
 * Resolve the extraction-plane per-call timeout ceiling :
 * K8 key ai.doc.llm-timeout-seconds wins for the three extraction-plane
 * calls only; unset/blank keeps the global {@link #resolve} face
 * byte-identical (the override is opt-in), garbage or values outside the
 * same 10~900 window the global key uses degrade to that face with a warn
 * — never silently off. Hot-update channel is shared with {@link #resolve}:
 * core's write path refreshes the cache, so a saved value takes effect on
 * the next call.
 */
    public Duration resolveDocExtract() {
        String raw = coreGateway == null ? null
                : coreGateway.getSysConfig("ai.doc.llm-timeout-seconds");
        if (raw != null && !raw.isBlank()) {
            try {
                int seconds = Integer.parseInt(raw.trim());
                if (seconds >= MIN_SECONDS && seconds <= MAX_SECONDS) {
                    return Duration.ofSeconds(seconds);
                }
                log.warn("K8 ai.doc.llm-timeout-seconds={} out of range (allowed {}-{}), keeping the global timeout",
                        raw, MIN_SECONDS, MAX_SECONDS);
            } catch (NumberFormatException e) {
                log.warn("K8 ai.doc.llm-timeout-seconds={} is not a valid integer, keeping the global timeout", raw);
            }
        }
        return resolve();
    }

    /**
 * Question-generation plane ceiling : opt-in tightening
 * mirroring {@link #resolveDocExtract} — the K8 key
 * ai.judge.qa-gen.llm-timeout-seconds wins for qa-gen group calls only;
 * unset/blank keeps the global {@link #resolve} face byte-identical;
 * garbage or out-of-window values (same 10~900 bounds) degrade to the
 * global face with a warn, never silently off. Hot-update channel shared
 * with {@link #resolve}: core's write path refreshes the cache, so a
 * saved value takes effect on the next call.
 */
    public Duration resolveJudgeQaGen() {
        String raw = coreGateway == null ? null
                : coreGateway.getSysConfig("ai.judge.qa-gen.llm-timeout-seconds");
        if (raw != null && !raw.isBlank()) {
            try {
                int seconds = Integer.parseInt(raw.trim());
                if (seconds >= MIN_SECONDS && seconds <= MAX_SECONDS) {
                    return Duration.ofSeconds(seconds);
                }
                log.warn("K8 ai.judge.qa-gen.llm-timeout-seconds={} out of range (allowed {}-{}), keeping the global timeout",
                        raw, MIN_SECONDS, MAX_SECONDS);
            } catch (NumberFormatException e) {
                log.warn("K8 ai.judge.qa-gen.llm-timeout-seconds={} is not a valid integer, keeping the global timeout", raw);
            }
        }
        return resolve();
    }

    /**
     * ONE shared seam composing a per-call ceiling under the caller's existing
     * clamp (extracted from the doc plane's private helper the moment the
     * judge plane became the second consumer — R40 single source). Shortens
     * only, never lengthens: a longer timeout is capped, a shorter one is
     * left alone, and an existing clamp still wins when it is tighter.
     */
    public static java.util.function.UnaryOperator<Duration> clampUnderCeiling(
            java.util.function.UnaryOperator<Duration> clamp, Duration ceiling) {
        if (ceiling == null) {
            return clamp;
        }
        return timeout -> {
            Duration capped = timeout == null || timeout.compareTo(ceiling) > 0
                    ? ceiling : timeout;
            return clamp == null ? capped : clamp.apply(capped);
        };
    }
}
