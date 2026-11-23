package com.sixbean.testpike.core.aichat.api.dto;

/**
 * One-shot confirmation ticket of a dialog draft (AO batch stage 1,
 * generalized from the repair-only ticket; 02
 * POST /internal/ai-chat/drafts/{id}/confirm-token response).
 * <p>Two-state contract mirrors the write-tool door: issuing parks a one-shot
 * ticket in Redis bound to the draft id; applying consumes it atomically
 * (GETDEL). Re-issuing after expiry or replay therefore cannot open the door.
 *
 * @param token random ticket string (SecureRandom 32B hex, unguessable)
 * @param expiresInSeconds live TTL window at issue time (dock countdown display)
 */
public record DraftTicket(String token, long expiresInSeconds) {
}
