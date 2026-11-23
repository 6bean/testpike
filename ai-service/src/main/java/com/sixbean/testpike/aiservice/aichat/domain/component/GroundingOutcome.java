package com.sixbean.testpike.aiservice.aichat.domain.component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Result of one grounding assembly (AO batch ; four-grade quality added
 * by AX batch item 6).
 * <p>The honesty contract is structural, not advisory: when nothing relevant
 * was found, {@link #zeroHit} is {@code true} with an empty block and no
 * citations — the orchestrator then tells the model it has no grounded
 * material and the answer must say so plainly instead of improvising
 * platform facts (adjudication A3: honest refusal, never fabricated sourcing).
 * <p>When something was found, {@link #quality} grades the pool
 * (high / weak relevance, cross-source conflict, suspected stale) and
 * {@link #warningLines} carries the naming sentences — the contradiction
 * field with its mutually exclusive values, or the stale material with its
 * last-write date — which also ride appended to {@link #contextBlock} so
 * the model must relay them to the user instead of silently picking a side.
 *
 * @param citations ranked, deduped, truncated citations (empty when zero-hit)
 * @param contextBlock prompt-side block listing {@code [C<n>]} marked excerpts
 * for the model to lean on, plus the naming lines when the
 * pool deserves them (empty string when zero-hit)
 * @param zeroHit true = retrieval found nothing to stand on
 * @param quality material grade of the pool; {@link GroundingQuality#NONE}
 * exactly when zero-hit
 * @param warningLines user-visible naming sentences for conflict/stale
 * (empty for high/weak — a weak pool changes wording
 * caution, not a named defect)
 * @param poolMoment the as-of moment the data-moment stamp names (latest
 * ledger write time among the grounded materials, read by
 * the retrieval channel — zero extra fetch); {@code null}
 * when no material recorded a write time or nothing was
 * found, so the display side renders no label instead of
 * inventing a clock (behaviour kernel round-2 item 5)
 */
public record GroundingOutcome(List<GroundingCitation> citations, String contextBlock,
                               boolean zeroHit, GroundingQuality quality,
                               List<String> warningLines, OffsetDateTime poolMoment) {

    /** Pre-moment shape kept for callers and fixtures that carry no stamp. */
    public GroundingOutcome(List<GroundingCitation> citations, String contextBlock,
                            boolean zeroHit, GroundingQuality quality,
                            List<String> warningLines) {
        this(citations, contextBlock, zeroHit, quality, warningLines, null);
    }

    /** Pre-grading shape: callers without a verdict keep a clean grounded pool. */
    public GroundingOutcome(List<GroundingCitation> citations, String contextBlock,
                            boolean zeroHit) {
        this(citations, contextBlock, zeroHit,
                zeroHit ? GroundingQuality.NONE : GroundingQuality.HIGH, List.of(), null);
    }

    /** The only honest shape of a zero-hit outcome. */
    public static GroundingOutcome none() {
        return new GroundingOutcome(List.of(), "", true,
                GroundingQuality.NONE, List.of(), null);
    }
}
