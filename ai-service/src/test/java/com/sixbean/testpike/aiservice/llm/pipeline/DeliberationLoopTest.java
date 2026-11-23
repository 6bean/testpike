package com.sixbean.testpike.aiservice.llm.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 ring spectrum of the multi-round reasoning confirmation loop
 * . Four sanctioned verdicts plus the
 * shared-verdict composition:
 * <ul>
 * <li>early stop — a converged claim set ends the ring inside the round it
 * converged, zero surplus calls;</li>
 * <li>non-convergence — injected contradiction must surface residual
 * disputes on the outcome, the ring is forbidden to pass silently;</li>
 * <li>hot key — rounds 1/2/3 each drive exactly that many verification
 * passes, read per call with no restart;</li>
 * <li>domain clamp — a domain may lower the round count but can never run
 * above the base value, and unreadable input falls back to the factory
 * default;</li>
 * <li>shared verdict — the default checker reuses EvidenceAnchorGate and
 * NumberProvenance as the only judgement source (no second rule set).</li>
 * </ul>
 * The roles are plain lambdas here: this piece is a pure in-process ring with
 * no I/O, so nothing is mocked — live wiring proofs ride the L3/L4 windows of
 * the consuming domains.
 */
class DeliberationLoopTest {

    @Test
    void convergedRunStopsBeforeTheSecondRound() {
        AtomicInteger proposals = new AtomicInteger();
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger revisions = new AtomicInteger();

        DeliberationOutcome outcome = DeliberationLoop.deliberate("任务说明", List.of("原文区块"), "2", 0,
                brief -> {
                    proposals.incrementAndGet();
                    return "初稿";
                },
                (draft, evidence) -> {
                    checks.incrementAndGet();
                    return List.of();
                },
                (draft, disputes) -> {
                    revisions.incrementAndGet();
                    return draft;
                });

        assertTrue(outcome.converged(), "empty dispute list must converge");
        assertEquals(1, proposals.get());
        assertEquals(1, checks.get(), "a converged round is never re-checked");
        assertEquals(0, revisions.get(), "nothing to revise after convergence");
        assertEquals(1, outcome.roundsUsed());
        assertEquals(2, outcome.modelCalls(), "propose + one verification only");
    }

    @Test
    void disputedRunSurfacesResidualClaimsAndNeverPassesSilently() {
        DeliberationOutcome outcome = DeliberationLoop.deliberate("任务说明", List.of("矛盾证据"), "2", 0,
                brief -> "草案",
                (draft, evidence) -> List.of("数值 12 条 无来源"),
                (draft, disputes) -> "修订稿");

        assertFalse(outcome.converged(), "a standing dispute can never self-certify");
        assertEquals(List.of("数值 12 条 无来源"), outcome.residualClaims(),
                "the residual disagreement is named verbatim for the preview card");
        assertEquals("修订稿", outcome.draft());
        assertEquals(2, outcome.roundsUsed());
        assertEquals(4, outcome.modelCalls(), "propose + verify + revise + verify");
    }

    @Test
    void roundsKeyDrivesTheNumberOfVerificationPasses() {
        // (16 §93): the hot key is a ceiling, not a quota —
        // with zero-new early stop in place, a stalled round ends the ring
        // before the ceiling. This contract therefore pins a PROGRESSING
        // verifier (fresh dispute content every pass), which must still run
        // exactly as many verification passes as the key says.
        for (int expectedRounds : new int[] {1, 2, 3}) {
            AtomicInteger checks = new AtomicInteger();
            DeliberationOutcome outcome = DeliberationLoop.deliberate("任务", List.of(),
                    String.valueOf(expectedRounds), 0,
                    brief -> "草案",
                    (draft, evidence) -> List.of("仍有分歧 第" + checks.incrementAndGet() + "轮"),
                    (draft, disputes) -> "草案-第" + disputes.size() + "版");

            assertEquals(expectedRounds, checks.get(),
                    "hot key value " + expectedRounds + " must equal the verification passes");
            assertEquals(expectedRounds, outcome.roundsUsed());
            assertFalse(outcome.converged());
        }
    }

    @Test
    void zeroProgressRoundEndsTheRingEarlyWithoutSurplusCalls() {
        // the verifier keeps naming the exact same disputes
        // (the round brought zero new progress) — the ring must not burn the
        // remaining rounds revising against a frozen dispute set. Ceiling 3,
        // stall detected at pass 2: propose + verify + revise + verify only.
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger revisions = new AtomicInteger();
        List<String> frozen = List.of("数值 12 条 无来源", "区块锚缺失");

        DeliberationOutcome outcome = DeliberationLoop.deliberate("任务", List.of("证据"), "3", 0,
                brief -> "草案",
                (draft, evidence) -> {
                    checks.incrementAndGet();
                    return frozen;
                },
                (draft, disputes) -> {
                    revisions.incrementAndGet();
                    return "草案-第" + revisions.get() + "版";
                });

        assertFalse(outcome.converged(), "a standing dispute never self-certifies");
        assertTrue(outcome.stalled(), "the frozen-dispute finish is ledger-visible");
        assertEquals(3, outcome.roundsPlanned(), "the ceiling stays 3 — early stop, no round cut");
        assertEquals(frozen, outcome.residualClaims(), "residual claims surface verbatim");
        assertEquals(2, checks.get(), "the third pass must not run against a frozen dispute set");
        assertEquals(1, revisions.get(), "no surplus revision after the stall is detected");
        assertEquals(2, outcome.roundsUsed());
        assertEquals(4, outcome.modelCalls(), "propose + verify + revise + verify, then stop");
    }

    @Test
    void domainMayClampDownButNeverAboveTheBase() {
        assertEquals(2, DeliberationLoop.effectiveRounds("2", 3), "3 clamps to base 2");
        assertEquals(1, DeliberationLoop.effectiveRounds("1", 3), "1 clamps to base 1");
        assertEquals(2, DeliberationLoop.effectiveRounds("2", 0), "0 means: take the base");
        assertEquals(3, DeliberationLoop.baseRounds("9"), "over-range clamps to the range top");
        assertEquals(2, DeliberationLoop.baseRounds("乱码"), "unreadable falls back to factory default");
        assertEquals(2, DeliberationLoop.baseRounds(null), "absent row falls back to factory default");
        assertEquals(2, DeliberationLoop.FACTORY_ROUNDS);
        assertEquals("ai.kernel.deliberation-rounds", DeliberationLoop.GOVERNANCE_KEY);

        AtomicInteger checks = new AtomicInteger();
        DeliberationLoop.deliberate("任务", List.of(), "1", 3,
                brief -> "草案",
                (draft, evidence) -> {
                    checks.incrementAndGet();
                    return List.of("仍有分歧");
                },
                (draft, disputes) -> draft);
        assertEquals(1, checks.get(), "the live ring honours the clamp, not the request");
    }

    @Test
    void defaultVerdictReusesTheKernelEvidenceGatesWithoutASecondRule() {
        List<String> clean = DeliberationLoop.verdictAgainstEvidence(
                "综上，结论成立 [C1]", List.of());
        assertTrue(clean.isEmpty(), "an anchored conclusion passes the shared verdict");

        List<String> disputed = DeliberationLoop.verdictAgainstEvidence(
                "总共 88 行，结论已达成", List.of());
        assertFalse(disputed.isEmpty(), "an unprovenanced figure opens a named dispute");

        List<String> sourced = DeliberationLoop.verdictAgainstEvidence(
                "统计结果共 88 行", List.of("查询返回 88 行"));
        assertEquals(EvidenceAnchorGate.finishNudgeOrNull("统计结果共 88 行", List.of("查询返回 88 行")) == null,
                sourced.isEmpty(), "the default verdict is the gate's own verdict, never a new rule");
    }
}
