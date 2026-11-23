package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Single-source reader for the non-blank string arrays of built-in FC tool
 * argument bodies : plan.propose steps and chat.clarify
 * options share one parse rule — model output is untrusted input, so a
 * missing/non-array field or any broken JSON yields an empty list and never
 * takes the dialog down. Both consumers delegate here; a future built-in
 * card tool reads through this same door.
 */
public final class ToolArgumentReader {

    private ToolArgumentReader() {
    }

    /** Non-blank trimmed strings of {@code field} — empty for broken bodies. */
    public static List<String> readNonBlankTextArray(ObjectMapper mapper,
                                                     String argumentsJson,
                                                     String field) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return List.of();
        }
        try {
            var node = mapper.readTree(argumentsJson).path(field);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> titles = new ArrayList<>();
            for (var item : node) {
                String title = item.isTextual() ? item.asText().trim() : "";
                if (!title.isEmpty()) {
                    titles.add(title);
                }
            }
            return List.copyOf(titles);
        } catch (Exception hallucinatedBody) {
            return List.of();
        }
    }
}
