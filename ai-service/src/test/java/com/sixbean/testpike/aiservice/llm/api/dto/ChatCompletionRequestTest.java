package com.sixbean.testpike.aiservice.llm.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ChatCompletionRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 序列化_结构符合OpenAI契约() throws Exception {
        ChatCompletionRequest req = new ChatCompletionRequest(
                "gpt-4o-mini",
                List.of(new ChatMessage("system", "你是测试专家"), new ChatMessage("user", "生成用例")),
                0.3,
                2000);
        String json = mapper.writeValueAsString(req);
        assertTrue(json.contains("\"model\":\"gpt-4o-mini\""));
        assertTrue(json.contains("\"messages\""));
        assertTrue(json.contains("\"role\":\"user\""));
        assertTrue(json.contains("\"content\":\"生成用例\""));
        assertTrue(json.contains("\"max_tokens\":2000"));
        assertTrue(json.contains("\"temperature\":0.3"));
    }

    @Test
    void of工厂_可选参数为null不序列化() throws Exception {
        ChatCompletionRequest req = ChatCompletionRequest.of("m", List.of(new ChatMessage("user", "x")));
        String json = mapper.writeValueAsString(req);
        assertTrue(json.contains("\"model\":\"m\""));
        assertEquals(-1, json.indexOf("max_tokens"));
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Test
    void 序列化_FC工具数组符合OpenAI契约() throws Exception {
        ChatCompletionRequest req = new ChatCompletionRequest(
                "m",
                List.of(new ChatMessage("user", "查一下")),
                null, null,
                List.of(new ChatToolDef(new ChatToolFunction(
                        "kb_search", "检索知识库",
                        mapper.readTree("{\"type\":\"object\",\"properties\":{}}")))),
                "auto");
        String json = mapper.writeValueAsString(req);
        assertTrue(json.contains("\"tools\":[{\"type\":\"function\""), json);
        assertTrue(json.contains("\"function\":{\"name\":\"kb_search\""), json);
        assertTrue(json.contains("\"description\":\"检索知识库\""), json);
        assertTrue(json.contains("\"parameters\":{\"type\":\"object\""), json);
        assertTrue(json.contains("\"tool_choice\":\"auto\""), json);
    }

    @Test
    void 序列化_无FC参数时tools与tool_choice不出现() throws Exception {
        ChatCompletionRequest legacy = new ChatCompletionRequest(
                "m", List.of(new ChatMessage("user", "x")), 0.2, 100);
        String json = mapper.writeValueAsString(legacy);
        assertEquals(-1, json.indexOf("tools"));
        assertEquals(-1, json.indexOf("tool_choice"));
        ChatCompletionRequest of = ChatCompletionRequest.of("m", List.of(new ChatMessage("user", "x")));
        assertEquals(-1, mapper.writeValueAsString(of).indexOf("tools"));
    }
}
