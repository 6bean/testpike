package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.aichat.ChatContextKeys;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders one session's whitelisted pinned context into fact lines for the
 * rolling assembly's pinned section a2; D4 waterline sentence
 * and the shared vocabulary added by .
 *
 * <p>The write door (core ChatSessionServiceImpl.normalizeContextWhitelist)
 * already refuses foreign keys and non-scalar values, so this read-side pass
 * is defence in depth only — keys outside the shared vocabulary
 * {@link ChatContextKeys#PINNED_RENDER_ORDER} and anything non-textual or
 * non-numeric are silently dropped, and a broken or absent payload renders
 * zero facts instead of throwing at a turn.
 *
 * <p>D4 waterline : the per-question route is stated as
 * otherwise; the pair never also appears as two bare key lines. With origin
 * but no per-question route (legacy sessions predating the fold) the origin
 * falls back to a plain fact line rather than a half-true sentence.
 *
 * <p>Emission order follows the fixed vocabulary order, never the payload's
 * key order, so the prompt prefix stays byte-stable for provider prompt
 * caching.
 */
public final class SessionPinnedFacts {

    private static final String KEY_ROUTE = "route";
    private static final String KEY_ORIGIN_ROUTE = "origin_route";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SessionPinnedFacts() {
    }

    /** One line per whitelisted scalar (waterline sentence folded in); never throws. */
    public static List<String> render(String contextJson) {
        if (contextJson == null || contextJson.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(contextJson);
        } catch (Exception e) {
            return List.of();
        }
        if (!root.isObject()) {
            return List.of();
        }
        String route = scalarText(root, KEY_ROUTE);
        String origin = scalarText(root, KEY_ORIGIN_ROUTE);
        List<String> facts = new ArrayList<>();
        if (route != null) {
            facts.add(origin == null
                    ? "本问发起页面 = " + route
                    : "本问发起页面 = " + route + "（会话建于 " + origin + "）");
        } else if (origin != null) {
            facts.add("会话建于: " + origin);
        }
        for (String key : ChatContextKeys.PINNED_RENDER_ORDER) {
            if (key.equals(KEY_ROUTE) || key.equals(KEY_ORIGIN_ROUTE)) {
                continue; // both fold into the waterline branch above
            }
            String text = scalarText(root, key);
            if (text != null) {
                facts.add(key + ": " + text);
            }
        }
        return List.copyOf(facts);
    }

    /** Stripped textual/numeric value, or null when absent, blank, or not scalar. */
    private static String scalarText(JsonNode root, String key) {
        JsonNode value = root.get(key);
        if (value == null || (!value.isTextual() && !value.isNumber())) {
            return null;
        }
        String text = value.asText().strip();
        return text.isEmpty() ? null : text;
    }
}
