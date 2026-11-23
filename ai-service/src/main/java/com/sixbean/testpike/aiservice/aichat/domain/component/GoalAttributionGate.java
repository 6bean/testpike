package com.sixbean.testpike.aiservice.aichat.domain.component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Goal-lifecycle attribution gate (behaviour kernel round-2 item 1): the
 * ledger's reverse guardrail. Every final answer self-reports, on its own
 * autonomous continuation loop reads that verdict through {@link #parse}
 * and decides whether an open ledger may be carried into the next answer:
 * stored trace; a missing declaration loads nothing at all — the fail-safe
 * direction the ruling pins (prefer losing continuity over polluting
 * correctness).
 * <p>Same governance shape as the goal-echo family: one factory-on K8 key,
 * an operator-tunable published clause row with the built-in constant as
 * the conservative fallback, and the tri-state switch read delegated to
 * {@link ChatBehaviorDirectives#switchOn} (R40 single source — one gate
 * rule for every AI switch family, never a second copy).
 */
public final class GoalAttributionGate {

    /** Factory-on governance key for the attribution clause and the carry guard. */
    public static final String KEY_ENABLED = "ai.chat.goal-attribution.enabled";

    /** Template family carrying the operator-tuned attribution wording. */
    public static final String TEMPLATE_NAME = "chat-goal-attribution-directives";

    /** Template domain of this family (shared with the behaviour directives). */
    public static final String TEMPLATE_DOMAIN = ChatBehaviorDirectives.TEMPLATE_DOMAIN;

    /** Published bucket status for the row lookup. */
    public static final int PUBLISHED_STATUS = ChatBehaviorDirectives.PUBLISHED_STATUS;

    /** Built-in conservative clause when no published row carries text. */
    public static final String CLAUSE =
            "【目标归属】每轮最终作答固定自报一行目标归属：紧跟目标回显行之后"
                    + "（本轮没有回显行时就是正文首行）另起一行输出「【目标归属】续旧」或"
                    + "「【目标归属】开新：<一句话说明新目标>」。本轮所答仍是在推进本会话"
                    + "未完成的目标账本（含本轮刚为该目标拟定计划）时写续旧；所答与未完成"
                    + "目标账本无关（换话题、新任务）或本会话从未有目标账本时写开新。"
                    + "该行只占一行，写完换行进入正文。";

    /** Line-anchored frame shape: the marker plus one of the two declarations. */
    private static final Pattern FRAME_LINE =
            Pattern.compile("\\s*【目标归属】\\s*[:：]?\\s*(续旧|开新).*");

    private GoalAttributionGate() {
    }

    /**
     * Tri-state gate read: a missing row or an unparsable value keeps the
     * guard on; only an explicit 0/false closes it (semantics owned by the
     * directive piece so every AI switch family gates identically).
     */
    public static boolean enabled(String rawValue) {
        return ChatBehaviorDirectives.switchOn(rawValue);
    }

    /**
 * Verdict of one final answer: the first line that carries a well-formed
 * without the line shape never counts, and null/blank answers are
 * FRAME_MISSING (the fail-safe reading).
 */
    public static GoalAttributionFrame parse(String answer) {
        if (answer == null || answer.isBlank()) {
            return GoalAttributionFrame.FRAME_MISSING;
        }
        for (String line : answer.lines().toList()) {
            Matcher matcher = FRAME_LINE.matcher(line);
            if (matcher.matches()) {
                return "续旧".equals(matcher.group(1))
                        ? GoalAttributionFrame.CONTINUE_OLD
                        : GoalAttributionFrame.OPEN_NEW;
            }
        }
        return GoalAttributionFrame.FRAME_MISSING;
    }

    /**
     * Clause-group text for the system head: the published row content when
     * it carries text, otherwise the built-in conservative clause.
     */
    public static String headClause(String publishedContent) {
        return publishedContent == null || publishedContent.isBlank()
                ? CLAUSE : publishedContent;
    }
}
