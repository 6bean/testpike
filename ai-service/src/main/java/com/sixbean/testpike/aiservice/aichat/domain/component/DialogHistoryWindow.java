package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ChatMessageRemote;

import java.util.List;

/**
 * Removes the in-flight prompt echo from the sealed dialog history (
 * contract ①).
 *
 * <p>Why this exists: core seals the user row of the CURRENT turn the moment
 * the run opens (listSealedMessages returns status &lt;&gt; 'streaming'), so a
 * fresh history read during request assembly still ends with exactly the
 * prompt the assembler re-appends as the live user message. Left alone, the
 * provider would receive the same question twice. The rule is deliberately
 * minimal — one trailing user row whose content equals the current prompt is
 * dropped, once; every other shape (assistant tail, differing text from a
 * mid-turn inject, blank prompt) passes through untouched, and an earlier
 * identical question in the history stays because it is a real past turn.
 */
public final class DialogHistoryWindow {

    private DialogHistoryWindow() {
    }

    /** The history minus the current turn's echoed user row, oldest first. */
    public static List<ChatMessageRemote> withoutInFlightPrompt(List<ChatMessageRemote> history,
                                                                String currentPrompt) {
        if (history.isEmpty() || currentPrompt == null || currentPrompt.isBlank()) {
            return history;
        }
        ChatMessageRemote last = history.get(history.size() - 1);
        if ("user".equals(last.role()) && currentPrompt.equals(last.content())) {
            return history.subList(0, history.size() - 1);
        }
        return history;
    }
}
