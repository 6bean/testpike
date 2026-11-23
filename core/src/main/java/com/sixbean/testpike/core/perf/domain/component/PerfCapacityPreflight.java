package com.sixbean.testpike.core.perf.domain.component;

import java.util.List;
import java.util.Map;

/**
 * Capacity pre-flight wording (CF-5, resource reservation self-check).
 * <p>A launch used to either queue silently or fail at claim time, leaving the
 * operator to discover pool shortages only after pressing start. This component
 * turns the SAME numbers the dispatch path already uses into one human sentence
 * for a read-only card: online fleet size, machines whose capability face
 * carries the run's engine, their idle capacity, and how many perf tasks are
 * already ahead in the shared dispatch queue.
 * <p>Red line honoured here: no second capability/online judgement is
 * implemented — the caller passes counts produced by
 * {@link com.sixbean.testpike.core.executor.domain.service.ExecutorService#onlineCapabilities()}
 * and {@link com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport#covers}
 * (GA-4 single source), so this class is pure wording projection and stays
 * trivially testable.
 * <p>Spectrum (each branch = one real pool state):
 * <ul>
 *   <li>no machine online → the queue contract ("auto-starts once a node comes
 *       online", same posture as {@link PerfQueueNote}'s empty-fleet line);</li>
 *   <li>online but none capable → names the engine, the fleet size, the 0-capable
 *       count and the install remedy (same actionable case
 *       {@link PerfEngineRoutingSupport} rejects at dispatch);</li>
 *   <li>capable + idle enough + nothing ahead → capacity sufficient;</li>
 *   <li>capable + idle short → waits for capacity release;</li>
 *   <li>tasks ahead → the ahead count is named regardless of capacity (queue
 *       order dominates, same consumption discipline as the claim path).</li>
 * </ul>
 * An unresolvable concurrency tier (null/&lt;=0, e.g. malformed pressure config)
 * claims nothing = null: the card stays silent, the launch gate owns rejection.
 */
public final class PerfCapacityPreflight {

    /**
     * Human wording for the pool outlook of one planned run, or null when no
     * honest claim can be made (unknown tier).
     *
     * @param requiredConcurrency peak concurrency tier of the run
     *                            (same derivation the launch gate uses)
     * @param engine              engine code of the run (K6 / GATLING)
     * @param onlineCount         executors online right now (same-source count)
     * @param capableCount        online executors whose capability face covers
     *                            the engine (GA-4 covers() projection)
     * @param idleCapacity        sum of (capacity - running_cnt) over the
     *                            capable executors
     * @param pendingAhead        perf dispatch tasks still pending with a
     *                            smaller id (PerfQueueNote same counting rule)
     */
    public static String noteOf(int requiredConcurrency, String engine,
                                int onlineCount, int capableCount,
                                int idleCapacity, int pendingAhead) {
        if (requiredConcurrency <= 0 || engine == null || engine.isBlank()) {
            return null;   // no honest tier/engine claim — silent card, gate rejects upstream
        }
        if (onlineCount == 0) {
            return "当前没有在线的压测执行机：任务将排队等待，任一执行机上线后会自动开始压测。";
        }
        if (capableCount == 0) {
            return "在线执行机 " + onlineCount + " 台中具备该能力的为 0 台：本压测需要引擎 "
                    + engine + "，任务无法派发执行。请在压测执行机安装 "
                    + ("K6".equals(engine) ? "k6 二进制并加入 PATH"
                            : "GATLING 运行环境（java 17+ 与 GATLING_LIB 引擎 jar 目录）")
                    + " 后重启执行器重新注册。";
        }
        StringBuilder note = new StringBuilder("具备 " + engine + " 能力的执行机 "
                + capableCount + " 台，空闲容量 " + idleCapacity + "，本次需要并发 "
                + requiredConcurrency + "。");
        if (pendingAhead > 0) {
            note.append("前方还有 ").append(pendingAhead).append(" 个压测任务排队，将按领取顺序开始压测。");
        } else if (idleCapacity >= requiredConcurrency) {
            note.append("容量充足，可立即开始压测。");
        } else {
            note.append("空闲容量不足，任务将等待容量释放后开始压测。");
        }
        return note.toString();
    }

    /** Sum of idle slots (capacity - running_cnt) over the given executor rows. */
    public static int idleCapacityOf(List<Map<String, Object>> rows) {
        int idle = 0;
        for (Map<String, Object> row : rows) {
            Object capacity = row.get("capacity");
            Object running = row.get("running_cnt");
            int cap = capacity instanceof Number n ? n.intValue() : 0;
            int run = running instanceof Number n ? n.intValue() : 0;
            idle += Math.max(0, cap - run);
        }
        return idle;
    }

    private PerfCapacityPreflight() {
    }
}
