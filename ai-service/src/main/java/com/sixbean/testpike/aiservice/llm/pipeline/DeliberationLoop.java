package com.sixbean.testpike.aiservice.llm.pipeline;

import java.util.Collection;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Multi-round reasoning confirmation ring of the behaviour kernel
 * (BA batch , user re-adjudication 2026-11-06, ruling section 10: every reasoning judgement
 * goes through the shared kernel — propose, verify claim by claim against the
 * evidence, revise the named defects — instead of one-shot answers).
 * <p>Role ports are plain function parameters so every reasoning domain
 * (extraction reconcile, scenario enumeration, faithfulness check, ground
 * truth check, judge replay, stage1/stage2 main judgement) wires its own model
 * calls through one shared ring instead of each re-deriving the loop — a
 * second per-domain copy would be the exact drift this piece forbids (R40).
 * The default checker composes the existing kernel gates
 * ({@link EvidenceAnchorGate} with {@link NumberProvenance}) and adds no new
 * judgement rule.
 * <p>Pure in-process scheduling, no I/O: a converged claim set stops the ring
 * early with zero surplus calls; a non-converged one surfaces the residual
 * disputes on the outcome for the preview/draft card (never silent
 * self-certification). Round count reads the hot K8 key
 * {@code ai.kernel.deliberation-rounds} per call — a domain may clamp it
 * down but can never exceed the base, and the base itself stays inside 1..3
 * whatever the row says. The produced text is always a draft/preview shape;
 * the three human gates (adoption checkbox, scenario selection, one-click
 * cost confirmation) stay entirely outside this piece.
 */
public final class DeliberationLoop {

    /** Governance row carrying the base round count (hot update, read per call). */
    public static final String GOVERNANCE_KEY = "ai.kernel.deliberation-rounds";

    /** Factory default when the row is absent, blank or unreadable. */
    public static final int FACTORY_ROUNDS = 2;

    /** Adjudicated value range: 1..3, clamped at the base whatever the row says. */
    private static final int MIN_ROUNDS = 1;
    private static final int MAX_ROUNDS = 3;

    private DeliberationLoop() {
    }

    /**
     * Base rounds from the raw config value: over-range clamps into 1..3, an
     * unreadable or absent value falls back to the factory default — a bad
     * row degrades the ring, it never takes the reasoning run down.
     */
    public static int baseRounds(String rawConfigValue) {
        if (rawConfigValue == null || rawConfigValue.isBlank()) {
            return FACTORY_ROUNDS;
        }
        try {
            int parsed = Integer.parseInt(rawConfigValue.trim());
            return Math.min(Math.max(parsed, MIN_ROUNDS), MAX_ROUNDS);
        } catch (NumberFormatException unreadable) {
            return FACTORY_ROUNDS;
        }
    }

    /**
     * Rounds this domain actually runs: {@code domainRequested <= 0} adopts
     * the base, anything above it clamps down — domains may trade cost for
     * speed, never the other way.
     */
    public static int effectiveRounds(String rawConfigValue, int domainRequested) {
        int base = baseRounds(rawConfigValue);
        if (domainRequested <= 0) {
            return base;
        }
        return Math.max(MIN_ROUNDS, Math.min(domainRequested, base));
    }

    /**
     * Runs one propose → verify → (revise → verify…) ring.
     *
     * @param taskBrief             what the proposer is asked to judge
     * @param evidenceTexts         evidence blocks for the checker (source
     *                              chunks / contract schema / data dictionary
     *                              / control inventory / acceptance criteria)
     * @param rawRoundsConfig       raw value of {@link #GOVERNANCE_KEY}
     * @param domainRequestedRounds 0 for the base, or a downward clamp
     * @param proposer              role 1: produces the judgement draft
     * @param verifier              role 2: returns the claims still disputed
     *                              against the evidence (empty = converged)
     * @param reviser               role 3: rewrites only the named defects
     */
    public static DeliberationOutcome deliberate(String taskBrief, List<String> evidenceTexts,
                                                 String rawRoundsConfig, int domainRequestedRounds,
                                                 Function<String, String> proposer,
                                                 BiFunction<String, List<String>, List<String>> verifier,
                                                 BiFunction<String, List<String>, String> reviser) {
        int rounds = effectiveRounds(rawRoundsConfig, domainRequestedRounds);
        String draft = proposer.apply(taskBrief);
        int calls = 1;
        List<String> residual = List.of();
        List<String> previousDisputes = null;
        for (int round = 1; round <= rounds; round++) {
            List<String> disputes = verifier.apply(draft, evidenceTexts);
            calls++;
            residual = disputes == null ? List.of() : disputes;
            if (residual.isEmpty()) {
                return new DeliberationOutcome(draft, List.of(), round, calls, true, rounds, false);
            }
            // (16 §93, ceiling-not-quota ruling): the round
            // named zero new progress — the dispute set is byte-for-byte the
            // previous pass, so further revise/verify rounds can only spin.
            // Stop inside the round the stall was detected and surface the
            // frozen claims (never a silent pass); ai.kernel.deliberation-
            // rounds itself stays untouched — early stop, no round cut.
            if (previousDisputes != null && residual.equals(previousDisputes)) {
                return new DeliberationOutcome(draft, residual, round, calls, false, rounds, true);
            }
            previousDisputes = residual;
            if (round < rounds) {
                draft = reviser.apply(draft, residual);
                calls++;
            }
        }
        return new DeliberationOutcome(draft, residual, rounds, calls, false, rounds, false);
    }

    /**
     * The shared default checker: the verdict IS the finish gate's verdict
     * (anchor rule plus no-mental-math rule composed, no second rule source),
     * an empty list when the draft already passes.
     */
    public static List<String> verdictAgainstEvidence(String draft, Collection<String> evidenceTexts) {
        String nudge = EvidenceAnchorGate.finishNudgeOrNull(draft, evidenceTexts);
        return nudge == null ? List.of() : List.of(nudge);
    }
}
