package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * What the browser gets back after steering a running answer.
 * <p>{@code injectedIntoRunningTurn} is the honest verdict of two different
 * outcomes in one door: {@code true} means the text landed on the answer that
 * is still streaming (it will replay as a user-inject frame at {@code seq}),
 * {@code false} means no answer was running, so the same text was submitted as
 * a regular message and {@code messageId} is the new assistant turn. Either
 * way the supplement survives — a steering sentence is never silently dropped.
 *
 * @param injectedIntoRunningTurn whether a running turn took the injection
 * @param messageId               the assistant turn that now carries the text
 * @param seq                     ledger seq of the landed frame (0 on degrade)
 */
public record ChatInjectView(boolean injectedIntoRunningTurn, long messageId, long seq) {
}
