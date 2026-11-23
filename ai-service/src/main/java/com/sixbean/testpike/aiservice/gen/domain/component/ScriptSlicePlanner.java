package com.sixbean.testpike.aiservice.gen.domain.component;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure operator that cuts a case's step list into contiguous groups so each
 * group can go out as its own model request ( S4 segment A; consumed by the
 * per-group dispatch added in segment C).
 *
 * <p>Invariants: order preserved, no overlap, no loss, the last group may be
 * short. A width of 0 (or any non-positive width) means "slicing off" and
 * returns the single whole group — the exact legacy one-pass shape, so the
 * shipped default can be disabled without any byte drift upstream.
 */
public final class ScriptSlicePlanner {

    private ScriptSlicePlanner() {
    }

    /**
     * @param steps      the case step items in authoring order (may be empty)
     * @param sliceWidth steps per group; 0 or negative = no slicing
     * @return contiguous groups; empty input yields no groups
     */
    public static <T> List<List<T>> plan(List<T> steps, int sliceWidth) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        if (sliceWidth <= 0 || sliceWidth >= steps.size()) {
            return List.of(steps);
        }
        List<List<T>> groups = new ArrayList<>();
        for (int from = 0; from < steps.size(); from += sliceWidth) {
            groups.add(steps.subList(from, Math.min(from + sliceWidth, steps.size())));
        }
        return groups;
    }
}
