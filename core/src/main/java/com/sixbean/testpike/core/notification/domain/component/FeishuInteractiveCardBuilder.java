package com.sixbean.testpike.core.notification.domain.component;

import com.sixbean.testpike.common.notify.ReadTokenSigner;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


public final class FeishuInteractiveCardBuilder {

    
    private static final Set<String> CARD_EVENTS = Set.of(
            "REVIEW_REMIND", "REVIEW_TIMEOUT", "DEFECT_UNASSIGNED");

    
    private static final Map<String, String> DEEP_LINK_PATH = Map.of(
            "REVIEW_REMIND", "/review",
            "REVIEW_TIMEOUT", "/review",
            "DEFECT_UNASSIGNED", "/defects");

    private FeishuInteractiveCardBuilder() {
    }

    
    public static boolean isCardEvent(String eventType) {
        return eventType != null && CARD_EVENTS.contains(eventType);
    }

    
    public static Map<String, Object> build(NotificationCreateInput input, long notificationId,
                                            String displayName, String baseUrl, String callbackKey) {
        String content = input.content();
        if (displayName != null && !displayName.isBlank()) {
            content = content + "\n负责人：" + displayName;
        }
        Map<String, Object> header = Map.of(
                "title", Map.of("tag", "plain_text", "content", input.title()));

        Map<String, Object> bodyDiv = new LinkedHashMap<>();
        bodyDiv.put("tag", "div");
        bodyDiv.put("text", Map.of("tag", "lark_md", "content", content));

        List<Map<String, Object>> actions = new java.util.ArrayList<>();
        String link = deepLink(baseUrl, input.eventType());
        if (link != null) {
            actions.add(Map.of(
                    "tag", "button",
                    "text", Map.of("tag", "plain_text", "content", "去处理"),
                    "type", "primary",
                    "url", link));
        }
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String tokenPayload = notificationId + ":" + input.userId() + ":" + ts;
        actions.add(Map.of(
                "tag", "button",
                "text", Map.of("tag", "plain_text", "content", "标记已读"),
                "type", "default",
                "value", Map.of(
                        "payload", tokenPayload,
                        "sig", ReadTokenSigner.sign(tokenPayload, callbackKey))));

        Map<String, Object> actionBlock = new LinkedHashMap<>();
        actionBlock.put("tag", "action");
        actionBlock.put("actions", actions);

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("config", Map.of("wide_screen_mode", true));
        card.put("header", header);
        card.put("elements", List.of(bodyDiv, actionBlock));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msg_type", "interactive");
        payload.put("card", card);
        return payload;
    }

    
    static String deepLink(String baseUrl, String eventType) {
        String path = eventType == null ? null : DEEP_LINK_PATH.get(eventType);
        if (baseUrl == null || baseUrl.isBlank() || path == null) {
            return null;
        }
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + path;
    }
}
