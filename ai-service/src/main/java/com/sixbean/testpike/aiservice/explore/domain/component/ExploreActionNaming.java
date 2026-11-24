package com.sixbean.testpike.aiservice.explore.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;

import java.util.List;

/**
 * Single source that turns a raw tool name into the short business label shown
 * on process-event frames. Labels come from the tool definitions themselves
 * (built-in catalog or project-registered tools), so the mapping can never
 * drift away from what the model was actually offered; the raw machine name
 * stays in the key=value detail face of the same frame.
 */
public final class ExploreActionNaming {

    /** Fallback for calls whose tool definition is not in the visible set. */
    public static final String UNKNOWN_LABEL = "自定义动作";

    /** On-screen labels stay short; longer descriptions are clipped here. */
    private static final int MAX_LABEL_CHARS = 20;

    private ExploreActionNaming() {
    }

    /**
     * First clause of the tool description (cut at the first punctuation that
     * starts an explanation aside), clipped to a compact action phrase.
     */
    public static String humanLabel(String toolName, List<ChatToolDef> visibleTools) {
        if (toolName == null || toolName.isBlank()) {
            return UNKNOWN_LABEL;
        }
        if (visibleTools != null) {
            for (ChatToolDef def : visibleTools) {
                if (def != null && def.function() != null
                        && toolName.equals(def.function().name())) {
                    String label = firstClause(def.function().description());
                    if (!label.isEmpty()) {
                        return label;
                    }
                }
            }
        }
        return UNKNOWN_LABEL;
    }

    private static String firstClause(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        String cut = description;
        for (char delimiter : new char[]{'，', '（', '(', '。', '；', ';'}) {
            int at = cut.indexOf(delimiter);
            if (at > 0) {
                cut = cut.substring(0, at);
            }
        }
        cut = cut.trim();
        return cut.length() <= MAX_LABEL_CHARS ? cut : cut.substring(0, MAX_LABEL_CHARS);
    }
}
