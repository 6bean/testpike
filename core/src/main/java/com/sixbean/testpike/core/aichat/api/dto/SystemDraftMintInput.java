package com.sixbean.testpike.core.aichat.api.dto;

/**
 * Linkage draft minting input .
 * <p>When the linkage bus detects that an upstream save invalidated a
 * downstream object whose edge disposition strategy is {@code grouped_draft},
 * and the AI repair generator posts back a usable snapshot, that snapshot
 * becomes one confirmable draft row through this input: {@code payloadJson}
 * is the repaired action JSON handed verbatim to the landing strategy,
 * {@code baseVersion} keeps the optimistic anchor the proposal was cut
 * against, {@code targetId} is the entity the write strategy aims at (the
 * owning case for a script repair), and {@code impactId} anchors the card to
 * its ledger row so a replayed snapshot updates the same row instead of
 * stacking cards. The card never writes business content by itself — the
 * one-click human accept behind the generic confirmation door does.
 */
public record SystemDraftMintInput(Long projectId, String objectType, Long targetId,
                                   Integer baseVersion, String schemaVersion,
                                   String modelName, String payloadJson,
                                   String summary, Long impactId) {
}
