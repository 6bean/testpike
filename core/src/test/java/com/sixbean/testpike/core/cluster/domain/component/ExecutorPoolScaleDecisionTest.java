package com.sixbean.testpike.core.cluster.domain.component;

import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolDemand;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolScaleSettings;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorScaleDecision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Autoscaler decision-core spectrum (T294c, pure rules, no Spring, no DB).
 * <p>Verified faces: ① demand depth divided by per-executor capacity rounded up;
 * ② P95 wait pressure adds one spare replica only at scale-out;
 * ③ pool min/max clamping (max is a hard ceiling even under deep queues);
 * ④ cooldown window suppresses further change and repeats the last desired value;
 * ⑤ scale-in always drains first (never remove a busy instance directly);
 * ⑥ elasticity-paused switch short-circuits to hold with a human-readable reason;
 * ⑦ budget-protection pause blocks scale-out while scale-in stays allowed;
 * ⑧ every reason line names the pool and points at the machine-pool page.
 */
class ExecutorPoolScaleDecisionTest {

    private static ExecutorPoolDemand demand(int pending, int currentReplicas, double p95WaitSeconds) {
        // activeExecutors=0 keeps these fixtures about queue depth alone; cooldown-neutral
        // (lastScale=100, now=1000 → 900s elapsed, outside the 600s window)
        return new ExecutorPoolDemand("android-emulator", pending, currentReplicas,
                0, p95WaitSeconds, 100);
    }

    private static ExecutorPoolScaleSettings settings() {
        return new ExecutorPoolScaleSettings(4, 1, 20, 300, 600, false, 0.85, true, 1_000L);
    }

    @Test
    void demandDepthDividedByCapacity_roundsUp() {
        ExecutorScaleDecision decision = ExecutorPoolScaleRules.decide(
                demand(9, 1, 0), settings(), 0.5);
        assertEquals(3, decision.desiredReplicas(), "9 pending / 4 per executor = 2.25 rounds up to 3");
        assertEquals(ExecutorScaleDecision.ACTION_SCALE_OUT, decision.action());
        assertEquals(3, decision.targetReplicas(), "scale-out target equals desired directly");
    }

    @Test
    void p95WaitAboveThreshold_addsOneSpareOnlyOnScaleOut() {
        ExecutorScaleDecision pressured = ExecutorPoolScaleRules.decide(
                demand(8, 1, 301), settings(), 0.5);
        assertEquals(3, pressured.desiredReplicas(),
                "8/4=2 demand plus one spare when P95 wait crosses the threshold");

        ExecutorScaleDecision alreadyMatching = ExecutorPoolScaleRules.decide(
                demand(8, 2, 301), settings(), 0.5);
        assertEquals(2, alreadyMatching.desiredReplicas(),
                "capacity already covers demand: no scale-out, so no pressure add-on");
        assertEquals(ExecutorScaleDecision.ACTION_HELD, alreadyMatching.action());
    }

    @Test
    void desiredClampedToPoolMinAndMax() {
        ExecutorScaleDecision floored = ExecutorPoolScaleRules.decide(
                demand(0, 1, 0), settings(), 0.5);
        assertEquals(1, floored.desiredReplicas(), "idle pool never drops under the configured minimum");

        ExecutorScaleDecision capped = ExecutorPoolScaleRules.decide(
                demand(500, 1, 0), settings(), 0.5);
        assertEquals(20, capped.desiredReplicas(), "max replicas is a hard ceiling even for a deep queue");
        assertTrue(capped.reason().contains("上限"), "reason honestly says the ceiling stopped the growth");
    }

    @Test
    void cooldownWindowSuppressesFurtherChange() {
        // 50/4=13 desired vs current 5: outside cooldown this would scale out;
        // the last scale happened 300s ago (lastScale=700, now=1000), inside the 600s window.
        ExecutorPoolDemand coolingDemand = new ExecutorPoolDemand(
                "android-emulator", 50, 5, 0, 0, 700);
        ExecutorScaleDecision cooling = ExecutorPoolScaleRules.decide(
                coolingDemand, settings(), 0.5);
        assertEquals(ExecutorScaleDecision.ACTION_COOLDOWN_HELD, cooling.action(),
                "900s of demand pressure cannot override the 600s cooldown window");
        assertEquals(5, cooling.desiredReplicas(), "cooldown repeats the current desired value");
        assertTrue(cooling.reason().contains("冷却窗口"), "reason names the cooldown as the cause");
    }

    @Test
    void scaleInDrainsBeforeRemoval() {
        // idle pool (no busy executors) at 6 replicas, outside the cooldown fixture
        ExecutorScaleDecision shrinking = ExecutorPoolScaleRules.decide(
                new ExecutorPoolDemand("android-emulator", 0, 6, 0, 0, 100), settings(), 0.5);
        assertEquals(1, shrinking.desiredReplicas(), "idle pool converges to the minimum");
        assertEquals(ExecutorScaleDecision.ACTION_SCALE_IN, shrinking.action());
        assertTrue(shrinking.drainFirst(), "scale-in must drain the instance before it is removed");
        assertEquals(5, shrinking.targetReplicas(),
                "scale-in target steps down gradually, never straight to desired in one jump");
    }

    @Test
    void elasticityPausedSwitchShortCircuitsToHold() {
        ExecutorPoolScaleSettings paused = new ExecutorPoolScaleSettings(4, 1, 20, 300, 600,
                true, 0.85, true, 1_000L);
        ExecutorScaleDecision growthWhilePaused = ExecutorPoolScaleRules.decide(
                demand(40, 2, 0), paused, 0.5);
        assertEquals(ExecutorScaleDecision.ACTION_PAUSED, growthWhilePaused.action(),
                "paused switch short-circuits even a deep queue");
        assertEquals(2, growthWhilePaused.desiredReplicas(), "paused keeps the current replica line");
        assertTrue(growthWhilePaused.reason().contains("android-emulator"),
                "paused reason names the affected pool");

        ExecutorScaleDecision idleWhilePaused = ExecutorPoolScaleRules.decide(
                demand(0, 4, 0), paused, 0.5);
        assertEquals(ExecutorScaleDecision.ACTION_PAUSED, idleWhilePaused.action(),
                "paused also freezes scale-in");
    }

    @Test
    void budgetProtectionBlocksScaleOutButNotScaleIn() {
        ExecutorScaleDecision blockedOut = ExecutorPoolScaleRules.decide(
                demand(40, 2, 0), settings(), 0.9);
        assertEquals(ExecutorScaleDecision.ACTION_BUDGET_PAUSED, blockedOut.action(),
                "budget utilization above the threshold pauses growth");
        assertEquals(2, blockedOut.desiredReplicas(), "growth is held at the current line");
        assertTrue(blockedOut.reason().contains("预算"), "reason names the budget guard as the cause");

        ExecutorScaleDecision allowedIn = ExecutorPoolScaleRules.decide(
                new ExecutorPoolDemand("android-emulator", 0, 6, 0, 0, 100), settings(), 0.9);
        assertEquals(ExecutorScaleDecision.ACTION_SCALE_IN, allowedIn.action(),
                "budget pause never blocks freeing capacity");

        ExecutorPoolScaleSettings guardOff = new ExecutorPoolScaleSettings(4, 1, 20, 300, 600,
                false, 0.85, false, 1_000L);
        ExecutorScaleDecision off = ExecutorPoolScaleRules.decide(
                demand(40, 2, 0), guardOff, 0.9);
        assertEquals(ExecutorScaleDecision.ACTION_SCALE_OUT, off.action(),
                "guard disabled by config restores normal growth");
    }

    @Test
    void reasonLinesStayUserFacing() {
        ExecutorScaleDecision decision = ExecutorPoolScaleRules.decide(
                demand(9, 1, 0), settings(), 0.5);
        String reason = decision.reason();
        assertTrue(reason.contains("android-emulator"), "reason names the pool");
        assertTrue(reason.contains("机群管理"), "reason points at the fleet page as the entry");
        for (String banned : new String[]{"裁决", "单源", "棘轮", "收口", "出账", "人话"}) {
            assertFalse(reason.contains(banned), "user-facing reason must not carry governance wording: " + banned);
        }
    }
}
