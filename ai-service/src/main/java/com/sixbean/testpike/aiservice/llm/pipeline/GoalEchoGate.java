package com.sixbean.testpike.aiservice.llm.pipeline;

import com.sixbean.testpike.aiservice.aichat.domain.component.ChatBehaviorDirectives;

import java.util.regex.Pattern;

/**
 * Goal-echo kernel piece : every dialog finish must open
 * operator's goal is visible before any work rides on it. Ambiguity is fixed
 * through the ordinary conversation — no new control surface is added.
 * <p>The echo clause group is operator-tunable exactly like the other
 * behaviour-directive families: the published {@code chat-goal-echo-directives}
 * template row wins, the built-in {@link #CLAUSE} is the conservative fallback,
 * and the tri-state gate semantics are delegated to
 * {@link ChatBehaviorDirectives#switchOn} (single source, R40 — a gate rule
 * must never drift per family).
 * <p>Echo and clarify are mutually exclusive within one turn: a round that
 * questions the user back carries the question and its options only, because
 * a restated goal plus an unresolved ambiguity would let the model proceed on
 * an assumption it is simultaneously asking about.
 */
public final class GoalEchoGate {

    /** Factory-on governance key for the echo clause and the finish-door lane. */
    public static final String KEY_ENABLED = "ai.chat.goal-echo.enabled";

    /** Template family carrying the operator-tuned echo wording. */
    public static final String TEMPLATE_NAME = "chat-goal-echo-directives";

    /** Template domain of this family (shared with the behaviour-directives). */
    public static final String TEMPLATE_DOMAIN = ChatBehaviorDirectives.TEMPLATE_DOMAIN;

    /** Published bucket status for the row lookup. */
    public static final int PUBLISHED_STATUS = ChatBehaviorDirectives.PUBLISHED_STATUS;

    /** Built-in conservative clause used when no published row carries text. */
    public static final String CLAUSE =
            "【目标回显】每轮最终作答的首行固定输出一句理解回显，格式："
                    + "理解为 <你复述的任务目标>｜约束 <本轮生效的前提、假设与边界>。"
                    + "该行只占一行，写完直接换行进入正文。本轮需要向用户追问时不输出回显行"
                    + "——回显与追问同一轮出现即为冲突，追问轮只出问题与选项。"
                    + "低风险歧义不要追问：按最可能的理解作答，把所做假设写进约束位。";

    /** Nudge handed back through the shared finish door when the line is missing. */
    public static final String NUDGE =
            "本轮最终作答缺少首行目标回显。请重写作答：第一行固定输出"
                    + "「理解为 <任务目标>｜约束 <本轮生效的前提、假设与边界>」，回显写完直接换行进入正文；"
                    + "本轮需要向用户追问时不写回显行——回显与追问同一轮互斥，追问轮只出问题与选项。";

    /** First-line echo shape: restatement slot and constraint slot both present. */
    private static final Pattern ECHO_LINE = Pattern.compile("\\s*理解为.+｜约束.+");

    private GoalEchoGate() {
    }

    /**
     * Tri-state gate read: a missing row or an unparsable value keeps the echo
     * on; only an explicit 0/false closes it (semantics owned by the directive
     * piece so every AI switch family gates identically).
     */
    public static boolean enabled(String rawValue) {
        return ChatBehaviorDirectives.switchOn(rawValue);
    }

    /**
     * True when the first line of the answer is a well-formed echo — both the
     * restatement and the constraint slot present on line one. An echo buried
     * below the first line does not count: the whole point is that the goal
     * restatement is the first thing the operator reads.
     */
    public static boolean firstLineHasEcho(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return ECHO_LINE.matcher(text.lines().findFirst().orElse("")).matches();
    }

    /**
     * Finish-door lane: an answer without the echo line gets exactly one nudge
     * naming the format and the clarify exemption. A null or blank answer is
     * not an echoed failure — nothing to nudge.
     */
    public static String finishNudgeOrNull(String answer) {
        if (answer == null || answer.isBlank() || firstLineHasEcho(answer)) {
            return null;
        }
        return NUDGE;
    }

    /**
     * Clause-group text for the system head: the published row content when it
     * carries text, otherwise the built-in conservative clause — the head is
     * never left without an echo instruction while the gate is open.
     */
    public static String headClause(String publishedContent) {
        return publishedContent == null || publishedContent.isBlank()
                ? CLAUSE : publishedContent;
    }

    /**
     * Conflict detector for one round: the turn questions the user back yet
     * still carries the echo line on its first line. Pure clarify rounds
     * (no echo) and pure answers (no question markers) are conflict-free.
     */
    public static boolean clarifyRoundConflict(String text) {
        if (!firstLineHasEcho(text)) {
            return false;
        }
        String rest = stripLeadingEcho(text);
        return rest.contains("追问") || rest.contains("问题")
                || rest.contains("？") || rest.contains("?");
    }

    /**
     * Drops the leading echo line so clarify detection judges the body only.
     * An echo standing alone without a body line is returned untouched —
     * stripping it would empty the text and hide the conflict instead of
     * exposing it.
     */
    public static String stripLeadingEcho(String text) {
        if (!firstLineHasEcho(text)) {
            return text;
        }
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(newline + 1);
    }
}
