package com.sixbean.testpike.common.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Title merge single source for the document extraction planes .
 *
 * <p>Two consumption shapes, one key normalization:
 * <ul>
 * <li>Mode A {@link #choosePreservingConfirmed} — retry merge: rows the
 * user already confirmed keep their identity when the same title is
 * re-offered, confirmed rows missing from the fresh round are kept at
 * the tail, everything unconfirmed is replaced by the fresh round.
 * Consumed by the core write-back of extraction previews.</li>
 * <li>Mode B {@link #foldFirstSeenByTitle} — cross-group fold: after
 * per-group direct extraction the same requirement can surface in
 * several groups; first-seen wins, later duplicates are dropped.
 * Consumed by the ai-service grouping plane.</li>
 * </ul>
 *
 * <p>Normalization (shared by both modes) folds NFKC full-width/half-width
 * variants, collapses whitespace runs, trims and case-folds — the legacy
 * verbatim comparison was defeated by a single added space, which is why
 * one implementation source exists here and nowhere else.
 */
public final class TitleIdempotentMerge {

    private TitleIdempotentMerge() {
    }

    /**
     * Normalized-title single source: NFKC folding (full-width to half-width
     * punctuation and letters, ideographic space to plain space), whitespace
     * run collapse, trim, case fold. Null answers the empty key.
     */
    public static String normalizeTitle(String raw) {
        if (raw == null) {
            return "";
        }
        String folded = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        return folded.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Mode A retry merge. Rows of {@code previous} that satisfy
     * {@code confirmedOf} and carry a usable title are indexed by normalized
     * title (later row wins a duplicate key, matching the legacy
     * LinkedHashMap.put behaviour). For every row of {@code current}, an
     * indexed confirmed row with the same title is adopted through
     * {@code onAdoptConfirmed} at that slot instead of the fresh row;
     * confirmed rows nobody re-offered keep their tail order through the
     * same adopt hook. Unconfirmed previous rows never survive — the fresh
     * round replaces them wholesale. A null {@code current} is an empty round.
     */
    public static <T> List<T> choosePreservingConfirmed(List<T> previous, List<T> current,
            Function<? super T, String> titleOf, Predicate<? super T> confirmedOf,
            UnaryOperator<T> onAdoptConfirmed) {
        Map<String, T> confirmedByTitle = new LinkedHashMap<>();
        for (T row : previous == null ? List.<T>of() : previous) {
            String title = titleOf.apply(row);
            if (confirmedOf.test(row) && hasText(title)) {
                confirmedByTitle.put(normalizeTitle(title), row);
            }
        }
        List<T> result = new ArrayList<>();
        for (T row : current == null ? List.<T>of() : current) {
            String title = titleOf.apply(row);
            T confirmed = hasText(title)
                    ? confirmedByTitle.remove(normalizeTitle(title))
                    : null;
            result.add(confirmed != null ? onAdoptConfirmed.apply(confirmed) : row);
        }
        for (T leftover : confirmedByTitle.values()) {
            result.add(onAdoptConfirmed.apply(leftover));
        }
        return result;
    }

    /**
     * Mode B cross-group fold: first seen row wins per normalized title,
     * later duplicates are dropped; rows without a usable title can never
     * collide, so they always pass through. Input order is preserved. This
     * is the identity hook of {@link #foldFirstSeenByTitle(List, Function,
     * BinaryOperator)} — one fold implementation (R40).
     */
    public static <T> List<T> foldFirstSeenByTitle(List<T> items,
            Function<? super T, String> titleOf) {
        return foldFirstSeenByTitle(items, titleOf, (survivor, dropped) -> survivor);
    }

    /**
 * Mode B cross-group fold with a merge hook ,
 * merge-preserved markers): first seen wins per normalized title, and
 * every dropped duplicate is routed through
 * {@code foldInto(survivor, duplicate)} into its survivor, so
 * identifiers planted in the dropped bodies stay recallable from the
 * surviving row instead of being silently swallowed. Keyless rows pass
 * through untouched; input order is preserved.
 */
    public static <T> List<T> foldFirstSeenByTitle(List<T> items,
            Function<? super T, String> titleOf, BinaryOperator<T> foldInto) {
        Map<String, Integer> survivorSlotByKey = new LinkedHashMap<>();
        List<T> folded = new ArrayList<>();
        for (T row : items == null ? List.<T>of() : items) {
            String title = titleOf.apply(row);
            if (!hasText(title)) {
                folded.add(row);
                continue;
            }
            Integer at = survivorSlotByKey.putIfAbsent(normalizeTitle(title), folded.size());
            if (at == null) {
                folded.add(row);
            } else {
                folded.set(at, foldInto.apply(folded.get(at), row));
            }
        }
        return folded;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
