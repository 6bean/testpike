package com.sixbean.testpike.core.executor.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;


@Component
public class SessionDeclarationSupport {

 
    public static final String LEASE_KIND = "session";

    private static final int REF_MAX = 128;

    private final ObjectMapper objectMapper;

    public SessionDeclarationSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    
    public String sessionKey(String actionJson) {
        JsonNode session = parse(actionJson);
        return session == null ? null : textOrEmpty(session.path("key"));
    }

    
    public boolean isSticky(String actionJson) {
        JsonNode session = parse(actionJson);
        return session != null && "sticky".equals(textOrEmpty(session.path("portability")));
    }

    
    public String leaseRef(long projectId, long envId, String sessionKey) {
        String prefix = projectId + ":" + envId + ":";
        String key = sessionKey.length() <= REF_MAX - prefix.length()
                ? sessionKey
                : Integer.toHexString(sessionKey.hashCode()) + "#" + sessionKey.length();
        return prefix + key;
    }

    
    public static String holderOf(long executorId) {
        return String.valueOf(executorId);
    }

    private JsonNode parse(String actionJson) {
        if (actionJson == null || actionJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(actionJson).path("session");
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String textOrEmpty(JsonNode node) {
        return node == null || !node.isTextual() || node.asText().isBlank() ? null : node.asText();
    }
}
