package com.sixbean.testpike.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class SnapshotJsonSanitizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitize(Map<String, Object> input) {
        return (Map<String, Object>) SnapshotJsonSanitizer.sanitizeDeep(input, objectMapper);
    }

    @Test
    void 敏感键大小写与前缀变体全掩码() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("Authorization", "Bearer abc123def456");
        input.put("cookie", "session=xyz789");
        input.put("X-Api-Key", "ak-123456");
        input.put("nested", Map.of("Password", "P@ssw0rd123", "TOKEN", "tk-999888",
                "secret-note", "sn-777"));
        Map<String, Object> out = sanitize(input);

        assertEquals("Bearer ***MASKED***", out.get("Authorization"),
                "Bearer 方案前缀保留、凭据值零留存（TC-V1-01 掩码链路）");
        assertEquals("***MASKED***", out.get("cookie"));
        assertEquals("***MASKED***", out.get("X-Api-Key"));
        Map<String, Object> nested = (Map<String, Object>) out.get("nested");
        assertEquals("***MASKED***", nested.get("Password"));
        assertEquals("***MASKED***", nested.get("TOKEN"));
        assertEquals("***MASKED***", nested.get("secret-note"));
    }

    @Test
    void DOM快照键递归剥离不进留存面() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("stepNo", 1);
        input.put("domSnapshot", "<html>huge</html>");
        input.put("assertions", List.of(Map.of("kind", "expect.status",
                "domSnapshot", Map.of("inner", "x"))));
        Map<String, Object> out = sanitize(input);

        assertFalse(out.containsKey("domSnapshot"), "顶层 DOM 键剥离（TC-V1-02）");
        List<Map<String, Object>> assertions =
                (List<Map<String, Object>>) out.get("assertions");
        assertFalse(assertions.get(0).containsKey("domSnapshot"), "嵌套层 DOM 键剥离");
        assertEquals("expect.status", assertions.get(0).get("kind"), "非敏感内容不动");
    }

    @Test
    void 内嵌JSON字符串递归净化且掩码后合法可解析() throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("body", "{\"token\":\"tk-abc\",\"user\":\"zhangsan\"}");
        Map<String, Object> out = sanitize(input);

        Object parsed = out.get("body");
        assertInstanceOf(Map.class, parsed, "内嵌 JSON 字符串解析为结构化留存");
        Map<?, ?> body = (Map<?, ?>) parsed;
        assertEquals("***MASKED***", body.get("token"));
        assertEquals("zhangsan", body.get("user"));
        String json = objectMapper.writeValueAsString(out);
        assertTrue(json.contains("\"token\":\"***MASKED***\""), "留存形态: " + json);
    }

    @Test
    void 非JSON文本按E5脱敏原样留存() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("note", "联系 13812345678 或 zhangsan@example.com 咨询");
        input.put("brokenJson", "{not valid json");
        Map<String, Object> out = sanitize(input);

        assertEquals("联系 138****5678 或 z***@example.com 咨询", out.get("note"),
                "E5 手机号/邮箱打码复用 DesensitizeUtils.maskText");
        assertEquals("{not valid json", out.get("brokenJson"),
                "非法 JSON 前缀文本按普通文本处理（宽容不抛栈）");
    }

    @Test
    void 列表嵌套与标量透传() {
        List<Object> list = new ArrayList<>();
        list.add(42);
        list.add(true);
        list.add(null);
        list.add(Map.of("api_key", "kk-1"));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("items", list);
        input.put("status", 200);
        Map<String, Object> out = sanitize(input);

        List<?> items = (List<?>) out.get("items");
        assertEquals(42, items.get(0));
        assertEquals(true, items.get(1));
        assertNull(items.get(2));
        assertEquals("***MASKED***", ((Map<?, ?>) items.get(3)).get("api_key"));
        assertEquals(200, out.get("status"));
    }
}
