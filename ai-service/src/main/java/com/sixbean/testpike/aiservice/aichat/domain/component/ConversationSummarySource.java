package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ChatMessageRemote;

import java.util.List;

/**
 * Lossy summary of the turns older than the raw window (AP-1 ②). The single
 * compressor contract: one call gets the exact older-history segment in
 * chronological order and returns its condensed text; blank/null = "no
 * summary to render" (never a fabricated section). The dialog segment wires the
 * document-chain summarisation contract (MultiLevelReducePlanner reduce face,
 * same prompt discipline) into this seam — the assembler itself stays pure.
 */
@FunctionalInterface
public interface ConversationSummarySource {

    /** Condensed text for the older turns, or blank when there is nothing worth folding. */
    String summarize(List<ChatMessageRemote> earlierHistory);
}
