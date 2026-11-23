package com.sixbean.testpike.aiservice.aichat.domain.component;

/**
 * Outcome of the failure-scene screenshot round-trip behind a resolved failure
 * diagnosis . Three shapes only,
 * mirroring the {@link FailureDiagnosis} posture so the orchestrator seam
 * stays one branch:
 * <ul>
 * <li>{@link #NONE} — nothing to try (vision application point switched off:
 * master gate ∧ sub-switch not both on, or no diagnosis on the table): the
 * turn runs exactly the deterministic text-only chain — off state leaves no
 * half-baked side effects, zero artifact reads, zero model calls;</li>
 * <li>{@link #absent} — the point is on but the picture could not be turned
 * into material (visual model unconfigured, no screenshot artifact, door
 * down, model failure): the turn still runs text-only and ONE honest state
 * frame names why, so "answered without the picture" is never mistaken for
 * "answered from it";</li>
 * <li>{@link #attached} — the vision model described the failure scene from
 * the artifact; the digest rides the system prompt next to the diagnosis
 * block, and the object key stays visible as the trace anchor.</li>
 * </ul>
 *
 * @param sceneBlock vision-model description of the failure scene ("" unless attached)
 * @param objectKey screenshot artifact anchor named in the frame (null unless attached)
 * @param absentReason non-null only in the degraded shape
 */
public record FailureScreenshotEvidence(String sceneBlock, String objectKey, String absentReason) {

    /** Nothing to try: silent pass-through of the deterministic text-only chain. */
    public static final FailureScreenshotEvidence NONE =
            new FailureScreenshotEvidence("", null, null);

    /** Scene recognized: the digest rides the prompt, the key is the trace anchor. */
    public static FailureScreenshotEvidence attached(String sceneBlock, String objectKey) {
        return new FailureScreenshotEvidence(sceneBlock, objectKey, null);
    }

    /** Point on but material missing: honest absence, the text-only turn still runs. */
    public static FailureScreenshotEvidence absent(String reason) {
        return new FailureScreenshotEvidence("", null, reason);
    }

    public boolean attached() {
        return sceneBlock != null && !sceneBlock.isBlank();
    }

    public boolean absent() {
        return absentReason != null;
    }
}
