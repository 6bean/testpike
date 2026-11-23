package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ChatMessageRemote;

import java.util.List;

/**
 * One dialog-request assembly shot for the rolling-summary order (AP-1 ②,
 * adjudication sheet 2026-10-26, .
 *
 * @param systemHead frozen system role text (built by ChatRunOrchestrator,
 * kept byte-stable across turns so provider-side prompt
 * caches still hit)
 * @param pinnedFacts confirmed-fact lines (user decisions / confirmed
 * parameters / current object) — a lossless layer,
 * rendered before the summary and never dropped by it
 * @param history closed turns fetched from core (chronological,
 * WITHOUT the in-flight current prompt)
 * @param currentPrompt the user question being answered now
 * @param keepRecentTurns how many newest turns keep raw text (ai.ctx
 * .session-keep-recent-turns, floor 1)
 */
public record ConversationAssemblyRequest(
        String systemHead,
        List<String> pinnedFacts,
        List<ChatMessageRemote> history,
        String currentPrompt,
        int keepRecentTurns) {

    public ConversationAssemblyRequest {
        pinnedFacts = pinnedFacts == null ? List.of() : List.copyOf(pinnedFacts);
        history = history == null ? List.of() : List.copyOf(history);
    }
}
