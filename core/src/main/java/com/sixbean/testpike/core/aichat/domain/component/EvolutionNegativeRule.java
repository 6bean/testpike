package com.sixbean.testpike.core.aichat.domain.component;

/**
 * One forbidden class of the self-evolution negative list (AF-i): a label
 * plus the case-insensitive pattern that recognises a proposal candidate
 * touching that surface.
 * <p>The table lives in {@link EvolutionNegativeGate}, which the tool-coverage
 * scanner reads verbatim; the gate's contract test parses both sides and
 * refuses any drift, so the two consumers always share one list.
 *
 * @param label   stable upper-snake name of the forbidden class
 * @param pattern case-insensitive regex recognising the surface
 */
public record EvolutionNegativeRule(String label, String pattern) {

    /** True when the candidate names a surface belonging to this forbidden class. */
    boolean hits(String candidate) {
        return candidate != null && java.util.regex.Pattern
                .compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(candidate)
                .find();
    }
}
