package com.sixbean.testpike.common.aibatch;

import java.util.ArrayList;
import java.util.List;

/**
 * Single-source normalization between the structured batch form and the legacy plain-text form
 * .
 * <p>Rule: when {@code items} is present and non-empty it wins; otherwise every legacy text
 * becomes a plain item with no requirement link. Both empty yields an empty list and the core
 * validation rejects it with the existing "at least one requirement" message.
 * Admin and core both consume this helper; no second copy of the merge rule is allowed.
 */
public final class BatchItemNormalizer {

    private BatchItemNormalizer() {
        // static utility; not instantiable by design
    }

    /** Normalize the dual-form request payload into the canonical item list. */
    public static List<AiBatchItem> normalize(List<AiBatchItem> items, List<String> legacyTexts) {
        if (items != null && !items.isEmpty()) {
            return items;
        }
        List<AiBatchItem> derived = new ArrayList<>();
        if (legacyTexts != null) {
            for (String text : legacyTexts) {
                derived.add(new AiBatchItem(text, null, null));
            }
        }
        return derived;
    }

    /** Extract the plain requirement texts from a normalized item list (cost estimation input). */
    public static List<String> texts(List<AiBatchItem> items) {
        List<String> texts = new ArrayList<>();
        for (AiBatchItem item : items) {
            texts.add(item == null ? null : item.text());
        }
        return texts;
    }
}
