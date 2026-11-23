package com.sixbean.testpike.core.aichat.api.dto;

/**
 * Result of landing a confirmable draft behind the frame door: the ledger row
 * id plus the woven action payload when the card-time duplicate check turned
 * a requirement create proposal into its two-choice form. A null woven
 * payload means the emitted single shape stays exactly as it was sent.
 */
public record DraftLanding(Long draftId, String wovenActionJson) {
}
