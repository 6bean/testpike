package com.sixbean.testpike.aiservice.aichat.domain.component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Shared grounding-pool fixture (single source per the duplication rule):
 * the kernel-side data-moment test and the display-side contract test grade
 * the SAME candidate shape through one knob set, so the two halves of the
 * as-of stamp can never drift apart.
 */
public final class GroundingPoolFixtures {

    public static final int MAX_CITATIONS = 5;
    public static final int EXCERPT_CHARS = 120;
    public static final double MIN_SCORE = 0.05;
    public static final double STRONG_SCORE = 0.35;
    public static final int STALE_WINDOW_DAYS = 0;

    private GroundingPoolFixtures() {
    }

    public static GroundingHit knowledge(long refId, String title, String content,
                                         OffsetDateTime updatedAt) {
        return new GroundingHit(GroundingKernel.KIND_KNOWLEDGE, refId, null,
                title, content, 0.4, null, false, updatedAt, null);
    }

    public static GroundingOutcome grade(String question, List<GroundingHit> candidates,
                                         OffsetDateTime now) {
        return GroundingKernel.assemble(question, candidates, MAX_CITATIONS,
                EXCERPT_CHARS, MIN_SCORE, true, STRONG_SCORE, STALE_WINDOW_DAYS, now);
    }
}
