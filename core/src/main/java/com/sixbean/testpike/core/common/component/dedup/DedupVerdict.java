package com.sixbean.testpike.core.common.component.dedup;

import java.util.List;

/**
 * Immutable outcome of one dedup gate evaluation.
 *
 * @param level           verdict band, see {@link DedupLevel}
 * @param normalizedTitle normalized probe title used for the deterministic key
 * @param matched         existing row named by an EXACT/HIGH verdict, null otherwise
 * @param candidates      top-N near neighbours for MEDIUM (empty otherwise),
 *                        ordered by descending similarity
 */
public record DedupVerdict(
        DedupLevel level,
        String normalizedTitle,
        DedupCandidate matched,
        List<DedupCandidate> candidates) {

    public static DedupVerdict exact(String normalizedTitle, DedupCandidate matched) {
        return new DedupVerdict(DedupLevel.EXACT, normalizedTitle, matched, List.of());
    }

    public static DedupVerdict high(String normalizedTitle, DedupCandidate matched) {
        return new DedupVerdict(DedupLevel.HIGH, normalizedTitle, matched, List.of());
    }

    public static DedupVerdict medium(String normalizedTitle, List<DedupCandidate> candidates) {
        return new DedupVerdict(DedupLevel.MEDIUM, normalizedTitle, null, candidates);
    }

    public static DedupVerdict pass(String normalizedTitle) {
        return new DedupVerdict(DedupLevel.PASS, normalizedTitle, null, List.of());
    }

    /** True when creating a new row must be refused and re-pointed to {@code matched}. */
    public boolean blocksCreate() {
        return level == DedupLevel.EXACT || level == DedupLevel.HIGH;
    }
}
