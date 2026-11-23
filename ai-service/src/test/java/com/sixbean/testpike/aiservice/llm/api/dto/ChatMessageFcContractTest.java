package com.sixbean.testpike.aiservice.llm.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ChatMessageFcContractTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    void tool消息序列化符合OpenAI契约且存量消息零漂移() throws Exception {
        ChatMessage toolMsg = ChatMessage.tool("call_1", "{\"hits\":3}");
        assertEquals("tool", toolMsg.role());
        String json = mapper.writeValueAsString(toolMsg);
        assertTrue(json.contains("\"tool_call_id\":\"call_1\""), json);
        assertFalse(json.contains("toolCalls"), "JSON 字段名必为 tool_call_id（驼峰泄漏=供应商不识别）");
        assertFalse(json.contains("tool_calls"), "tool 消息不带 tool_calls 字段（null 不序列化）");
        String plain = mapper.writeValueAsString(new ChatMessage("user", "hi"));
        assertEquals("{\"role\":\"user\",\"content\":\"hi\"}", plain);
    }

    @Test
    void assistant消息回写tool_calls忠实镜像模型输出() throws Exception {
        LlmToolCall call = new LlmToolCall("call_9", "kb_search", "{\"q\":\"login\"}");
        ChatMessage assistant = ChatMessage.assistantWithToolCalls("我要查工具", List.of(call));
        String json = mapper.writeValueAsString(assistant);
        assertTrue(json.contains("\"role\":\"assistant\""), json);
        assertTrue(json.contains("\"id\":\"call_9\""), json);
        assertTrue(json.contains("\"type\":\"function\""), json);
        assertTrue(json.contains("\"name\":\"kb_search\""), json);
        assertTrue(json.contains("\\\"q\\\""), "arguments 原文字符串化：" + json);
        assertNull(assistant.toolCallId(), "assistant 消息无 tool_call_id");
    }
}
