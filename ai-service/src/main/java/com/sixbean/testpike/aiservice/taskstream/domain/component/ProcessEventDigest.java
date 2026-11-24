package com.sixbean.testpike.aiservice.taskstream.domain.component;

/**
 * Single source for the one-line digest of free text written into process
 * event rows. Tail-drawer rows must stay readable as log lines: whitespace is
 * collapsed and long text is capped, while the full payload remains in the
 * detail json. A step that produced no readable text renders a business
 * placeholder instead of an empty or implementer-view row. Shared by every
 * producer face that digests model or step output (exploration loop steps,
 * document extraction answers) so the on-screen wording has exactly one
 * source and cannot drift between faces.
 */
public final class ProcessEventDigest {

    /** Rendered cap for one tail row; the rest stays in the detail payload. */
    private static final int ROW_CAP = 140;

    /** Placeholder rendered when a step returned no readable text. */
    private static final String EMPTY_STEP_ROW = "(本步骤没有返回内容)";

    private ProcessEventDigest() {
    }

    /** Collapse whitespace and cap the text to one readable tail row. */
    public static String digest(String text) {
        if (text == null || text.isBlank()) {
            return EMPTY_STEP_ROW;
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= ROW_CAP ? collapsed : collapsed.substring(0, ROW_CAP) + "…";
    }
}
