package com.sixbean.testpike.aiservice.llm.domain.component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * single-source bounded group fan-out (R40: one pool
 * implementation serves the doc main-judgement group loop, the spec contract
 * group loop, the judge question-generation group loop and the existing
 * final-bucket plane; a fourth private pool is forbidden).
 *
 * <p>Contract (asserted in {@code GroupParallelFanoutTest}):
 * <ul>
 * <li>results are returned in TASK order under any parallelism, so the
 * downstream first-seen-wins idempotent merge sees the same sequence
 * whether the run stayed serial or rode the pool (group-order
 * independence is mechanical, not verbal);</li>
 * <li>{@code parallel <= 1} executes every task inline on the caller
 * thread — the serial dual-spectrum baseline spawns no pool at all;</li>
 * <li>fail-closed: the first failed task aborts the wait loop and its
 * RuntimeException propagates UNWRAPPED (the generation-abort
 * vocabulary stays recognisable upstream), siblings are interrupted
 * by {@code shutdownNow}; a checked failure is wrapped exactly once
 * with its cause reachable;</li>
 * <li>pool workers are daemon threads carrying the caller-supplied name
 * prefix for thread-dump observability.</li>
 * </ul>
 *
 * <p> adds the budget-abort face: the 4-arg overload consumes a
 * {@link RunBudgetAbort} signal and, once it trips, NEVER launches an
 * unstarted task (serial checkpoint before every inline call; parallel
 * checkpoint before every submit and inside the wait poll), interrupts the
 * in-flight siblings and stops the caller waiting within one poll window,
 * throwing {@link GroupFanoutAbortException} with the ordered partial list.
 * The legacy 3-arg entry delegates with a null signal — every existing
 * consumer keeps the byte-identical fail-closed path (zero drift).
 *
 * <p>The K8 hot key {@code ai.pipeline.group-parallel} (cross-domain
 * pipeline namespace per A3 — never borrowed into ai.doc.*) defaults
 * to 3 and is hard-capped at 3 ( adjudicated cap; raising it is a
 * separate named decision). Reading stays "fresh read per call, no cache"
 * like every other K8 face — this class only parses and clamps.
 */
public final class GroupParallelFanout {

    /** Cross-domain K8 key governing every group-level fan-out. */
    public static final String KEY_GROUP_PARALLEL = "ai.pipeline.group-parallel";

    /** Factory default parallelism (also the fallback for garbage values). */
    public static final int DEFAULT_PARALLEL = 3;

    /** Hard cap: configuration may lower to 1 (serial), never raise past 3. */
    public static final int HARD_CAP = 3;

    /** Wait-loop checkpoint cadence while an abort signal is attached: the
     *  caller escapes a stuck sibling within one poll of the gate trip. */
    static final long ABORT_POLL_MILLIS = 100L;

    private GroupParallelFanout() {
    }

    /** K8 raw value → effective parallelism: blank/garbage → default; values
     *  above the hard cap clamp down; non-positive clamps to serial. */
    public static int parallelismOf(String rawConfig) {
        int parsed = SysConfigInts.parseInt(rawConfig, DEFAULT_PARALLEL);
        return Math.max(1, Math.min(HARD_CAP, parsed));
    }

    /**
     * Run every task and return their values in task order. With
     * {@code parallel <= 1} (or a single task) the calls stay inline on the
     * caller thread; otherwise a fixed daemon pool of
     * {@code min(parallel, tasks.size())} workers is created per call and
     * always released in the {@code finally} face.
     */
    public static <T> List<T> runAll(List<Callable<T>> tasks, int parallel,
                                     String threadNamePrefix) {
        return runAll(tasks, parallel, threadNamePrefix, null);
    }

    /**
     * Same contract with an optional run-level budget signal. While the
     * signal is untouched nothing changes; once it trips, unstarted tasks
     * are never launched, in-flight siblings are cancelled (interrupt), and
     * the caller throws {@link GroupFanoutAbortException} carrying the
     * ordered partial list (landed values, null per cancelled/unstarted
     * slot) — the gate-after provider-call increment stays exactly 0.
     */
    public static <T> List<T> runAll(List<Callable<T>> tasks, int parallel,
                                     String threadNamePrefix, RunBudgetAbort abort) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        if (parallel <= 1 || tasks.size() == 1) {
            List<T> inOrder = new ArrayList<>(tasks.size());
            for (Callable<T> task : tasks) {
                if (abort != null && abort.aborted()) {
                    throw abortException(abort, pad(inOrder, tasks.size()));
                }
                inOrder.add(callUnchecked(task));
            }
            return inOrder;
        }
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(parallel, tasks.size()), daemonFactory(threadNamePrefix));
        try {
            List<Future<T>> futures = new ArrayList<>(tasks.size());
            for (Callable<T> task : tasks) {
                if (abort != null && abort.aborted()) {
                    break;
                }
                futures.add(pool.submit(abortGuarded(task, abort)));
            }
            List<T> inOrder = new ArrayList<>(tasks.size());
            for (Future<T> future : futures) {
                inOrder.add(await(future, abort, futures, inOrder, tasks.size()));
            }
            if (abort != null && abort.aborted() && inOrder.size() < tasks.size()) {
                // The gate tripped mid submission: the never-submitted tail
                // must surface as cancelled slots, not as a short list.
                cancelAll(futures);
                throw abortException(abort, pad(inOrder, tasks.size()));
            }
            return inOrder;
        } finally {
            pool.shutdownNow();
        }
    }

    private static <T> T await(Future<T> future, RunBudgetAbort abort,
                               List<Future<T>> futures, List<T> landed, int total) {
        while (true) {
            if (abort != null && abort.aborted() && !future.isDone()) {
                cancelAll(futures);
                throw abortException(abort, pad(landed, total));
            }
            try {
                // Bounded poll on BOTH routes (defect A): an unbounded blocking
                // join would pin the caller thread whenever no abort handle was
                // passed. The inner provider hops are floored and bounded by the
                // factory seam (R56), so this loop only waits on work that is
                // itself time-bounded.
                return future.get(ABORT_POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException poll) {
                // loop back into the abort checkpoint above
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("group fan-out wait interrupted", interrupted);
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("group fan-out execution failed: " + cause, cause);
            }
        }
    }

    /**
 * worker-side entry guard. A queued task can be handed to a freed
 * worker while the waiting caller has not yet run its cancel pass (the
 * tripping task publishes the signal BEFORE its own future completes, so
 * any worker starting afterwards sees it): the guarded callable then
 * presents the slot as a never-run null instead of executing the task.
 * Without this guard the handoff race would let one sibling launch its
 * provider call right after the gate trip.
 */
    private static <T> Callable<T> abortGuarded(Callable<T> task, RunBudgetAbort abort) {
        if (abort == null) {
            return task;
        }
        return () -> abort.aborted() ? null : task.call();
    }

    private static <T> void cancelAll(List<Future<T>> futures) {
        for (Future<T> future : futures) {
            future.cancel(true);
        }
    }

    private static <T> GroupFanoutAbortException abortException(RunBudgetAbort abort,
                                                                List<T> landedInOrder) {
        return new GroupFanoutAbortException(abort.reason(), landedInOrder);
    }

    /** Extend the landed prefix with null slots up to the full task count so
     *  the partial list stays index-aligned with task order. */
    private static <T> List<T> pad(List<T> landed, int total) {
        List<T> full = new ArrayList<>(landed);
        while (full.size() < total) {
            full.add(null);
        }
        return full;
    }

    private static <T> T callUnchecked(Callable<T> task) {
        try {
            return task.call();
        } catch (RuntimeException | Error inline) {
            throw inline;
        } catch (Exception checked) {
            throw new IllegalStateException("group fan-out execution failed: " + checked, checked);
        }
    }

    private static java.util.concurrent.ThreadFactory daemonFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + seq.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
