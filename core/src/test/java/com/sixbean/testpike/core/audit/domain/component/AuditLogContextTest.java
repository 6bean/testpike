package com.sixbean.testpike.core.audit.domain.component;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class AuditLogContextTest {

    @AfterEach
    void clear() {
        AuditLogContext.clear();
    }

    @Test
    void setBefore后takeBefore返回并清空() {
        Map<String, Object> before = Map.of("status", "待评审");
        AuditLogContext.setBefore(before);
        assertEquals("待评审", AuditLogContext.takeBefore().get("status"),
                "takeBefore 必须返回已设置的快照");
        assertNull(AuditLogContext.takeBefore(), "takeBefore 取后必须清空（防线程复用串扰）");
    }

    @Test
    void 未设置时takeBefore返回null() {
        assertNull(AuditLogContext.takeBefore(), "未设置快照时必须返回 null（切面兜底不误写）");
    }

    @Test
    void AuditSnapshot敏感字段打码() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "GPT-4o");
        raw.put("apiKey", "sk-proj-abc123");
        raw.put("baseUrl", "http://localhost:18080/v1");
        Map<String, Object> masked = AuditSnapshot.masked(raw);
        assertEquals("GPT-4o", masked.get("name"), "普通字段不打码");
        assertEquals("******", masked.get("apiKey"), "apiKey 必须打码");
        assertEquals("http://localhost:18080/v1", masked.get("baseUrl"), "URL 字段不打码");
    }

    @Test
    void AuditSnapshot嵌套Map与下划线键打码() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("provider", "openai");
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("api_key", "sk-xxx");
        inner.put("token", "tkn-1");
        nested.put("meta", inner);
        Map<String, Object> masked = AuditSnapshot.masked(nested);
        @SuppressWarnings("unchecked")
        Map<String, Object> innerMasked = (Map<String, Object>) masked.get("meta");
        assertEquals("******", innerMasked.get("api_key"), "下划线 api_key 必须打码");
        assertEquals("******", innerMasked.get("token"), "token 必须打码");
        assertEquals("openai", masked.get("provider"), "普通字段不打码");
    }

    @Test
    void AuditSnapshot空值不打码保留原样() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("apiKey", null);
        raw.put("name", "");
        Map<String, Object> masked = AuditSnapshot.masked(raw);
        assertNull(masked.get("apiKey"), "null 值保持 null 不打码");
        assertEquals("", masked.get("name"), "空串保持空串");
        assertTrue(masked.containsKey("apiKey"), "null 键必须保留（diff 完整性：字段不能丢）");
    }
}
