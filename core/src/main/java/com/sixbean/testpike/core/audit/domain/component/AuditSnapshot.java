package com.sixbean.testpike.core.audit.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


public final class AuditSnapshot {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    
    private static final List<String> SENSITIVE_KEY_PATTERNS = List.of(
            "apikey", "api_key", "secret", "password", "passwd", "token",
            "privatekey", "private_key", "accesskey", "access_key", "authorization");

    private static final String MASK = "******";

    private AuditSnapshot() {
    }

    
    public static Map<String, Object> of(Object bean) {
        if (bean == null) {
            return null;
        }
        if (bean instanceof Map<?, ?> map) {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            map.forEach((k, v) -> snapshot.put(String.valueOf(k), v));
            return snapshot;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> converted = MAPPER.convertValue(bean, Map.class);
        return converted == null ? null : new LinkedHashMap<>(converted);
    }

    
    public static Map<String, Object> masked(Map<String, Object> snapshot) {
        if (snapshot == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
            result.put(entry.getKey(), maskValue(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    
    public static Map<String, Object> snapshot(Object bean) {
        return masked(of(bean));
    }

    private static Object maskValue(String key, Object value) {
        if (isSensitiveKey(key)) {
            return value == null ? null : MASK;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((k, v) -> nested.put(String.valueOf(k), maskValue(String.valueOf(k), v)));
            return nested;
        }
        if (value instanceof List<?> list) {
            List<Object> maskedList = new ArrayList<>(list.size());
            for (Object item : list) {
                maskedList.add(maskValue("", item));
            }
            return maskedList;
        }
        return value;
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        for (String pattern : SENSITIVE_KEY_PATTERNS) {
            if (lower.contains(pattern)) {
                return true;
            }
        }
        return false;
    }
}
