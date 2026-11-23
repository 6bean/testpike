package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigInts;
import com.sixbean.testpike.aiservice.llm.pipeline.StallFingerprintGuard;
import org.springframework.stereotype.Component;

/**
 * Stall-reflection streak bar : resolves the K8 key
 * {@code ai.behavior.stall-block-attempts} into the constructor argument of
 * {@link StallFingerprintGuard} at the point the guard is built.
 * <p>K8 hot-update posture identical to {@link FcRoundPolicy} /
 * {@link ToolFanoutPolicy}: every read hits the live value (the guard itself
 * is per-run state, so a value change lands on the next run without a
 * restart); an unreadable / illegal / non-positive value falls back to the
 * shipped default — a broken config row must never switch the guard off
 * <p>The value range authority stays on the core write side (K8 key group,
 * no DB CHECK — same ruling as the sibling policy keys).
 */
@Component
public class StallBlockPolicy {

    /** K8 key name, single-sourced from the guard constant (no drift). */
    public static final String KEY = StallFingerprintGuard.KEY_BLOCK_AFTER;

    /** Shipped default = the migration seed value (fallback only). */
    static final int DEFAULT_BLOCK_AFTER = 2;

    private final CoreGateway coreGateway;

    public StallBlockPolicy(CoreGateway coreGateway) {
        this.coreGateway = coreGateway;
    }

    /** Current effective bar: consecutive same-fingerprint failures of one
     *  call shape tolerated before the next identical call is refused. */
    public int blockAfterAttempts() {
        return SysConfigInts.positiveInt(coreGateway.getSysConfig(KEY), DEFAULT_BLOCK_AFTER);
    }
}
