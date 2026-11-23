package com.sixbean.testpike.core.session.api.dto;

import java.util.List;

/**
 * Internal stage request for one server-parsed DevTools panel screenshot
 * . The ai-service attachment parsing chain runs the
 * OCR and grid aligner locally and pushes the freshly parsed rows — values
 * included — through this door so they park in the same TTL-bound transient
 * store family the CDP wizard commit round already owns (one key spelling,
 * one window, R40). The response is only ever a staging token: values go in,
 * nothing value-bearing comes back out (piece E red line).
 *
 * @param channel staging provenance marker; {@link #CHANNEL_CHAT_SCREENSHOT}
 * today, kept open so a second parser cannot silently reuse a
 * slot staged by another shape
 * @param sessionId the chat session that produced the screenshot (H1
 * attribution anchor — the draft strategy refuses a card whose
 * session differs)
 * @param entries parsed rows with their values; names must be unique because
 * the draft-card checklist authorizes by name
 */
public record CaptureSlotStageInput(String channel, long sessionId,
                                    List<CaptureCommittedEntry> entries) {

    /** Staging channel for rows parsed from chat screenshot attachments. */
    public static final String CHANNEL_CHAT_SCREENSHOT = "chat-screenshot";

    /** Hard bound of one stage call (R57: transient surfaces are bounded too). */
    public static final int MAX_ENTRIES = 2000;
}
