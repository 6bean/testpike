package com.sixbean.testpike.aiservice.llm.pipeline;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stall reflection guard : an agent that repeats the
 * same failing call burns tokens and trust without learning. Once the same
 * failure fingerprint (tool name x normalized error form, see
 * {@link FailureTriage}) has been recorded {@code blockAfter} times in a
 * row, every further call of that SAME shape (tool + arguments) is refused
 * up-front and the caller gets one guidance frame forcing a strategy change
 * among the three sanctioned exits: variant query, clarify, or an honest
 * residual answer.
 * <p>Deliberate boundaries: a different argument shape stays open (variant
 * query IS the point); an intervening success clears the streak; a different
 * error form restarts counting from one (the guard blocks repetition of the
 * same mistake, not a tool that behaves differently next time). Pure in-memory
 * per-run state — one instance per ReAct run, wired where the runner lives;
 * the kernel itself touches no infrastructure so it is trivially testable.
 * <p>The streak bar arrives from K8 {@code ai.behavior.stall-block-attempts}
 * at the wiring point (hot-updatable, never hardcoded into the loop).
 */
public final class StallFingerprintGuard {

    /** K8 key the wiring resolves into {@code blockAfter} (shipped default 2). */
    public static final String KEY_BLOCK_AFTER = "ai.behavior.stall-block-attempts";

    /** One recorded failure streak for one call shape. */
    private record Streak(String fingerprint, String errorKind, int count) {
    }

    private final int blockAfter;
    private final Map<String, Streak> streaks = new ConcurrentHashMap<>();

    /**
     * @param blockAfter consecutive same-fingerprint failures tolerated before
     *                   the same call shape is refused (wiring passes the K8
     *                   value; values below 1 are clamped to 1 — zero would
     *                   mean "never retry anything", not this contract)
     */
    public StallFingerprintGuard(int blockAfter) {
        this.blockAfter = Math.max(1, blockAfter);
    }

    /**
     * Ask before dispatching: may this exact call shape run?
     *
     * @param toolName tool the model wants to call
     * @param callArgs serialized argument payload (the call shape)
     * @return blocked verdict + guidance frame, or the streak-carrying
     *         allowed verdict
     */
    public StallVerdict checkBeforeCall(String toolName, String callArgs) {
        Streak streak = streaks.get(key(toolName, callArgs));
        if (streak == null) {
            return StallVerdict.allowedFresh();
        }
        if (streak.count() >= blockAfter) {
            return new StallVerdict(true, streak.count(), streak.errorKind(),
                    guidanceFrame(toolName, streak));
        }
        return StallVerdict.allowedWith(streak.count(), streak.errorKind());
    }

    /**
     * Record that a dispatched call failed, keeping the raw error text for
     * fingerprinting. Same fingerprint in a row increments the streak; a
     * different error form replaces the streak at one.
     */
    public void recordFailure(String toolName, String callArgs, String errorText) {
        String key = key(toolName, callArgs);
        String fingerprint = FailureTriage.fingerprint(toolName, errorText);
        String kind = FailureTriage.classify(errorText);
        streaks.compute(key, (ignored, previous) ->
                previous != null && previous.fingerprint().equals(fingerprint)
                        ? new Streak(fingerprint, kind, previous.count() + 1)
                        : new Streak(fingerprint, kind, 1));
    }

    /** A success clears the slate for that exact call shape. */
    public void recordSuccess(String toolName, String callArgs) {
        streaks.remove(key(toolName, callArgs));
    }

    /**
     * Strategy-change frame: names the tool, how many times it failed the
     * same way, what kind of fault it looks like, and the three sanctioned
     * exits. This text is relayed toward the model (and through it, the
     * user), so it carries plain operational language only.
     */
    private String guidanceFrame(String toolName, Streak streak) {
        return "工具「" + toolName + "」已连续 " + streak.count()
                + " 次以同类错误失败（错误类型：" + FailureTriage.labelOf(streak.errorKind())
                + "），第 " + (streak.count() + 1) + " 次相同参数的调用已被系统拒绝。"
                + "请从以下三种策略中任选其一继续：①调整查询条件或对象，换一种方式重新获取；"
                + "②向用户追问以澄清意图或补充缺失信息；③如实告知用户该数据暂时无法取得，"
                + "并基于已获取的信息给出结论。同类错误重试不再会被放行。";
    }

    private static String key(String toolName, String callArgs) {
        return (toolName == null ? "?" : toolName) + "\u0000" + (callArgs == null ? "" : callArgs);
    }
}
