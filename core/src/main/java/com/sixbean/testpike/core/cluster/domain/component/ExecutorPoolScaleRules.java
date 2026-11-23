package com.sixbean.testpike.core.cluster.domain.component;

import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolDemand;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolScaleSettings;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorScaleDecision;

/**
 * Autoscaler decision core for executor pools (T294c; pure rules, no Spring, no DB).
 * <p>Evaluation order — the first guard that fires owns the answer:
 * <ol>
 *   <li>elasticity-paused switch short-circuits everything and keeps the current line;</li>
 *   <li>demand depth = pending dispatches / capacity per executor rounded up, never below the
 *       executors already handling work (a running task reserves its slot);</li>
 *   <li>P95 wait above the threshold adds one spare replica, only when the pool is growing
 *       (a pool already covering its demand must not churn);</li>
 *   <li>clamp to the pool min/max — max is a hard ceiling and the reason says so honestly;</li>
 *   <li>budget-protection pause blocks growth while freeing capacity stays allowed;</li>
 *   <li>cooldown window after the last scale event suppresses further change;</li>
 *   <li>scale-out jumps straight to the desired line; scale-in steps down one replica per tick
 *       and always drains first so no in-flight task is killed.</li>
 * </ol>
 * <p>Every threshold arrives through {@link ExecutorPoolScaleSettings} from cluster.* config
 * keys (hot-reloadable, nothing hard-coded); every reason line is user-facing wording for the
 * fleet page.
 */
public final class ExecutorPoolScaleRules {

    private ExecutorPoolScaleRules() {
    }

    /** Decide one pool's next scale step from its demand snapshot and guardrail settings. */
    public static ExecutorScaleDecision decide(ExecutorPoolDemand demand,
                                               ExecutorPoolScaleSettings settings,
                                               double budgetUtilization) {
        String pool = demand.executorType();
        int current = Math.max(0, demand.currentReplicas());

        if (settings.elasticPaused()) {
            return new ExecutorScaleDecision(current, current, ExecutorScaleDecision.ACTION_PAUSED,
                    false,
                    String.format("池 %s 的自动伸缩已被手动暂停，机群管理页可恢复。", pool));
        }

        int perExecutor = Math.max(1, settings.capacityPerExecutor());
        int pendingCapacity = Math.ceilDiv(Math.max(0, demand.pendingDispatchDepth()), perExecutor);
        int desired = Math.max(pendingCapacity, Math.max(0, demand.activeExecutors()));

        boolean growing = desired > current;
        if (growing && demand.p95WaitSeconds() > settings.p95WaitThresholdSeconds()) {
            desired = desired + 1;
        }

        int clamped = Math.min(Math.max(desired, settings.minReplicas()), settings.maxReplicas());
        String clampNote = "";
        if (desired > settings.maxReplicas()) {
            clampNote = String.format("（已按池上限 %d 封顶）", settings.maxReplicas());
        } else if (desired < settings.minReplicas()) {
            clampNote = String.format("（已按池下限 %d 保底）", settings.minReplicas());
        }

        boolean budgetPausing = settings.budgetPauseEnabled()
                && budgetUtilization >= settings.budgetMaxUtilization();
        if (budgetPausing && clamped > current) {
            return new ExecutorScaleDecision(current, current, ExecutorScaleDecision.ACTION_BUDGET_PAUSED,
                    false,
                    String.format("池 %s 的增长已暂停：预算水位 %.0f%% 达到阈值 %.0f%%，在机群管理页可调高阈值或关闭预算保护。",
                            pool, budgetUtilization * 100, settings.budgetMaxUtilization() * 100));
        }

        long sinceLastScale = Math.max(0, settings.nowEpochSeconds() - demand.lastScaleEpochSeconds());
        if (clamped != current && sinceLastScale < settings.cooldownSeconds()) {
            return new ExecutorScaleDecision(current, current, ExecutorScaleDecision.ACTION_COOLDOWN_HELD,
                    false,
                    String.format("池 %s 距上次伸缩 %d 秒，仍在 %d 秒冷却窗口内，本轮不变更。",
                            pool, sinceLastScale, settings.cooldownSeconds()));
        }

        if (clamped > current) {
            return new ExecutorScaleDecision(clamped, clamped, ExecutorScaleDecision.ACTION_SCALE_OUT,
                    false,
                    String.format("池 %s 待派发 %d 个，期望副本 %d（当前 %d）%s，机群管理页可查看伸缩详情。",
                            pool, demand.pendingDispatchDepth(), clamped, current, clampNote));
        }
        if (clamped < current) {
            return new ExecutorScaleDecision(clamped, current - 1, ExecutorScaleDecision.ACTION_SCALE_IN,
                    true,
                    String.format("池 %s 待派发 %d 个，期望副本 %d（当前 %d）%s，缩容先排空再下线副本。",
                            pool, demand.pendingDispatchDepth(), clamped, current, clampNote));
        }
        return new ExecutorScaleDecision(clamped, clamped, ExecutorScaleDecision.ACTION_HELD,
                false,
                String.format("池 %s 容量与待派发持平，副本保持 %d。", pool, clamped));
    }
}
