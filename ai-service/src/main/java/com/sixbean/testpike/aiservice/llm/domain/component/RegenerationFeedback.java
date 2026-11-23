package com.sixbean.testpike.aiservice.llm.domain.component;

import java.util.List;

/**
 * Shared renderer for regeneration-loop feedback sections (AP-4 single source: the
 * F2 requirement-extraction loop and the AiGen stage loops both fold previous rejection
 * reasons back into the next user prompt; the identical "- item" rendering lives here
 * only once — per-chain copies would drift).
 * <p>An empty/absent feedback list renders to the empty string so a first attempt stays
 * byte-identical to the no-feedback baseline.
 */
public final class RegenerationFeedback {

    private RegenerationFeedback() {
    }

    /**
     * Render the accumulated rejection reasons as "- item" lines under {@code header}.
     *
     * @param feedback rejection texts collected from previous attempts (may be null/empty)
     * @param header   human-readable section title written into the prompt
     * @return the prompt section, or "" when there is nothing to fold back
     */
    public static String render(List<String> feedback, String header) {
        if (feedback == null || feedback.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(header).append('\n');
        for (String f : feedback) {
            sb.append(" - ").append(f).append('\n');
        }
        return sb.toString();
    }
}
