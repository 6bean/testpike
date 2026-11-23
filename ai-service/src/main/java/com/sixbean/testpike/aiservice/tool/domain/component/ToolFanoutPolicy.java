package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigInts;
import org.springframework.stereotype.Component;

/**
 * Read-only tool fan-out policy (behavior-kernel tenet 10 / ): how many
 * independent read-only tool calls one ReAct round may run at the same time,
 * and how much wall clock the fan-out has to buy to be worth keeping.
 * <p>K8 hot-reload baseline (FcRoundPolicy/ScriptLimitResolver same posture):
 * every {@link #maxParallel} / {@link #targetRatio} call re-reads
 * {@code tp_sys_config} through the core gateway — no caching, so an
 * adjustment lands on the next dispatch. An unreadable, blank, unparsable or
 * out-of-range value falls back to the shipped default (fail-soft: a broken
 * tuning row must never widen concurrency or break the generation chain).
 * <p>The ratio is an acceptance/observability bound, not a behavior switch:
 * the measured fan-out wall clock is expected to stay under
 * {@code serial baseline x ratio}; a group that misses it is logged so the
 * operator can raise the cap or turn the fan-off knob (cap=1) without a deploy.
 */
@Component
public class ToolFanoutPolicy {

    /** Concurrency cap key (shipped default {@value #DEFAULT_MAX_PARALLEL}). */
    public static final String KEY_MAX_PARALLEL = "ai.tool.fanout-max-parallel";

    /** Wall-clock target ratio key (shipped default {@value #DEFAULT_TARGET_RATIO}). */
    public static final String KEY_TARGET_RATIO = "ai.tool.fanout-target-ratio";

    /** Shipped cap: four independent reads at a time; 1 = fan-out off (serial). */
    static final int DEFAULT_MAX_PARALLEL = 4;

    /** Shipped acceptance ratio: fan-out must beat serial by 40% for ≥3 reads. */
    static final double DEFAULT_TARGET_RATIO = 0.6d;

    private final CoreGateway coreGateway;

    public ToolFanoutPolicy(CoreGateway coreGateway) {
        this.coreGateway = coreGateway;
    }

    /** Current cap, fresh read (values below 1 are clamped to the default). */
    public int maxParallel() {
        return SysConfigInts.positiveInt(coreGateway.getSysConfig(KEY_MAX_PARALLEL),
                DEFAULT_MAX_PARALLEL);
    }

    /** Current acceptance ratio, fresh read; outside (0,1] → shipped default. */
    public double targetRatio() {
        String raw = coreGateway.getSysConfig(KEY_TARGET_RATIO);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TARGET_RATIO;
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            return parsed > 0 && parsed <= 1 ? parsed : DEFAULT_TARGET_RATIO;
        } catch (NumberFormatException ignored) {
            return DEFAULT_TARGET_RATIO;
        }
    }

    /** Wall-clock join cap key (shipped default {@value #DEFAULT_JOIN_CAP_SECONDS}). */
    public static final String KEY_JOIN_CAP = "ai.tool.fanout-join-cap-seconds";

    /**
     * Shipped join cap: a fan-out tool call that is still running after this
     * many seconds is cancelled and reported as a readable rejection instead
     * of pinning the round forever (defect A: no unbounded blocking join).
     */
    static final int DEFAULT_JOIN_CAP_SECONDS = 600;

    /** Below this the watchdog would fire on ordinary slow reads (clamp). */
    static final int MIN_JOIN_CAP_SECONDS = 30;

    /** Above this a hung call would outlive any plausible ReAct round. */
    static final int MAX_JOIN_CAP_SECONDS = 7200;

    /** Current join cap, fresh read; outside [30, 7200] → shipped default. */
    public int joinCapSeconds() {
        int parsed = SysConfigInts.positiveInt(coreGateway.getSysConfig(KEY_JOIN_CAP),
                DEFAULT_JOIN_CAP_SECONDS);
        return parsed < MIN_JOIN_CAP_SECONDS || parsed > MAX_JOIN_CAP_SECONDS
                ? DEFAULT_JOIN_CAP_SECONDS : parsed;
    }
}
