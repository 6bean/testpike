package com.sixbean.testpike.aiservice.aichat.domain.component;

import java.util.List;

/**
 * Outcome of the dialog user-image vision round-trip (AO batch P4 segment
 * 2c-B). Three shapes, mirroring {@link FailureScreenshotEvidence} so the
 * orchestrator seam stays one branch per leg — without reusing that record:
 * a failure-scene screenshot is one artifact behind a diagnosis, user images
 * are 0..N photos attached to the question itself, and the counts differ in
 * the trace frame, so the semantics stay deliberately separate.
 * <ul>
 * <li>{@link #NONE} — nothing to try (no attachments, or this application
 * point's sub-switch off): the turn runs the exact deterministic text-only
 * chain, zero door reads, zero model calls;</li>
 * <li>{@link #absent} — the point is on but no image became material (master
 * gate off, visual model unconfigured, every read leg failed): ONE honest
 * reason names why, the text-only answer still runs;</li>
 * <li>{@link #attached} — at least one image was described; the block
 * carries every observation and, in a mixed outcome, names the images that
 * could not be read (never a silently dropped photo).</li>
 * </ul>
 *
 * @param observationBlock model observations joined per image ("" unless attached)
 * @param absentReason non-null only in the degraded shape
 * @param readCount images actually described by the vision model
 * @param stagingTokens DevTools-panel staging tokens THIS round parked in
 * the transient core slots . The token
 * is a slot pointer, not a credential value — the same
 * handle the observation block already shows the model
 * (piece E red line untouched). The draft emitter
 * reconciles a transcribed pointer that routes to a
 * dead slot against this list at its single
 * consumption point.
 */
public record ChatUserImageEvidence(String observationBlock, String absentReason, int readCount,
                                    List<String> stagingTokens) {

    /** Nothing to try: silent pass-through of the deterministic text-only chain. */
    public static final ChatUserImageEvidence NONE =
            new ChatUserImageEvidence("", null, 0, List.of());

    /** At least one image described: the block rides the system prompt. */
    public static ChatUserImageEvidence attached(String observationBlock, int readCount) {
        return attached(observationBlock, readCount, List.of());
    }

    /** Attached form carrying this round's live staging tokens (B1 reconcile input). */
    public static ChatUserImageEvidence attached(String observationBlock, int readCount,
                                                 List<String> stagingTokens) {
        return new ChatUserImageEvidence(observationBlock, null, readCount,
                stagingTokens == null ? List.of() : List.copyOf(stagingTokens));
    }

    /** Point on but no image became material: honest absence, text-only turn runs. */
    public static ChatUserImageEvidence absent(String reason) {
        return new ChatUserImageEvidence("", reason, 0, List.of());
    }

    public ChatUserImageEvidence {
        stagingTokens = stagingTokens == null ? List.of() : List.copyOf(stagingTokens);
    }

    public boolean attached() {
        return observationBlock != null && !observationBlock.isBlank();
    }

    public boolean absent() {
        return absentReason != null;
    }

    /** Image labels carried alongside failed legs (mixed outcome keeps them named). */
    public static String joinFailures(List<String> failureLines) {
        return String.join("；", failureLines);
    }
}
