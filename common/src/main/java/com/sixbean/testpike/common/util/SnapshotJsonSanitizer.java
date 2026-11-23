package com.sixbean.testpike.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public final class SnapshotJsonSanitizer {

    
    private static final List<String> SENSITIVE_KEY_TOKENS = List.of(
            "authorization", "authenticate", "cookie", "password", "token", "secret", "apikey");

    
    public static final String MASKED = "***MASKED***";

    
    private static final String[] AUTH_SCHEME_PREFIXES = {"bearer ", "basic ", "digest ", "token "};

    
    private static final String DOM_SNAPSHOT_KEY = "domSnapshot";

    private SnapshotJsonSanitizer() {
    }

    
    public static Object sanitizeDeep(Object value, ObjectMapper objectMapper) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String key = String.valueOf(k);
                if (DOM_SNAPSHOT_KEY.equals(key)) {
                    return;
                }
                if (isSensitiveKey(key)) {
                    out.put(key, maskValue(v));
                } else {
                    out.put(key, sanitizeDeep(v, objectMapper));
                }
            });
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(item -> out.add(sanitizeDeep(item, objectMapper)));
            return out;
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    return sanitizeDeep(objectMapper.readValue(trimmed, Object.class), objectMapper);
                } catch (Exception ignored) {
                }
            }
            return DesensitizeUtils.maskText(s);
        }
        return value;
    }

    
    private static boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase().replace("_", "").replace("-", "").replace(" ", "");
        return SENSITIVE_KEY_TOKENS.stream().anyMatch(normalized::contains);
    }

    
    private static Object maskValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s && !s.isBlank()) {
            for (String scheme : AUTH_SCHEME_PREFIXES) {
                if (s.length() > scheme.length()
                        && s.substring(0, scheme.length()).equalsIgnoreCase(scheme)) {
                    return s.substring(0, scheme.length()) + MASKED;
                }
            }
        }
        return MASKED;
    }
}
