package com.sixbean.testpike.aiservice.llm.pipeline;

import java.util.Locale;

/**
 * Failure triage single source : the three-way verdict
 * the on-chain discipline already uses (transient environment / stale
 * expectation / genuine product fault) now lives HERE and nowhere else, so
 * the stall guard, the wiring tick and any later consumer share one
 * classification instead of drifting apart (R40 — second consumer, one piece).
 * <p>Classifying by word family keeps the piece dependency-free and
 * deterministic; anything unrecognized lands in the genuine bucket rather
 * than vanishing into a silent default — an unclassified fault is still a
 * real fault the user deserves to hear about.
 */
public final class FailureTriage {

    /** Transient transport/environment fault — retrying later may work. */
    public static final String ENVIRONMENT = "environment";
    /** The expected contract drifted (missing field/endpoint) — the caller's knowledge is stale. */
    public static final String STALE_EXPECTATION = "stale-expectation";
    /** Real product/validation fault — no retry strategy will change it. */
    public static final String GENUINE_FAILURE = "genuine-failure";

    /** Max length kept from the normalized first line (fingerprints stay small). */
    private static final int FORM_CAP = 120;

    private static final String[] ENVIRONMENT_WORDS = {
            "timed out", "timeout", "connection refused", "unreachable",
            "could not connect", "connection reset", "reset by peer",
            "service unavailable", "bad gateway", "gateway timeout",
            "503", "502", "504", "name or service not known", "temporary failure in name resolution",
    };

    private static final String[] STALE_EXPECTATION_WORDS = {
            "unknown field", "no such", "not found", "unresolved",
            "missing column", "no property", "unknown property", "schema", "404",
    };

    /**
     * Type an error message into one of the three buckets. Environment wins
     * over expectation-staleness because a transport fault behind a 404-ish
     * text should never push the agent to rewrite its contract knowledge.
     *
     * @param errorText raw failure text from the tool call, may be null
     * @return one of {@link #ENVIRONMENT}, {@link #STALE_EXPECTATION},
     *         {@link #GENUINE_FAILURE}
     */
    public static String classify(String errorText) {
        if (errorText == null || errorText.isBlank()) {
            return GENUINE_FAILURE;
        }
        String haystack = errorText.toLowerCase(Locale.ROOT);
        for (String word : ENVIRONMENT_WORDS) {
            if (haystack.contains(word)) {
                return ENVIRONMENT;
            }
        }
        for (String word : STALE_EXPECTATION_WORDS) {
            if (haystack.contains(word)) {
                return STALE_EXPECTATION;
            }
        }
        return GENUINE_FAILURE;
    }

    /**
     * Readable Chinese name of a bucket — these labels are relayed into the
     * guidance frame the model shows toward the user, hence plain language.
     */
    public static String labelOf(String kind) {
        return switch (kind) {
            case ENVIRONMENT -> "环境瞬时故障（网络/服务不可达）";
            case STALE_EXPECTATION -> "预期与现状不一致（字段或接口已变更）";
            default -> "业务处理失败";
        };
    }

    /**
     * Normalize the fault into a comparable form: first line only (stack
     * traces vary per occurrence), lowercased, whitespace collapsed, digit
     * runs replaced by {@code #} so "row 3 rejected at step 17" and
     * "row 91 rejected at step 4" are the same fault with different counts.
     */
    public static String normalizeForm(String errorText) {
        if (errorText == null || errorText.isBlank()) {
            return "";
        }
        String firstLine = errorText.strip().lines().findFirst().orElse("");
        String normalized = firstLine.toLowerCase(Locale.ROOT)
                .replaceAll("\\d+", "#")
                .replaceAll("\\s+", " ")
                .strip();
        return normalized.length() <= FORM_CAP ? normalized : normalized.substring(0, FORM_CAP);
    }

    /**
 * Stall fingerprint: tool name x normalized error form. Two failures of
 * the same tool in the same normalized shape are the same fingerprint
 */
    public static String fingerprint(String toolName, String errorText) {
        return (toolName == null ? "?" : toolName) + "|" + normalizeForm(errorText);
    }

    private FailureTriage() {
    }
}
