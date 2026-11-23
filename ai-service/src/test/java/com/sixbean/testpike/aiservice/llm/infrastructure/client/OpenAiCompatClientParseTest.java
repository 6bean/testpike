package com.sixbean.testpike.aiservice.llm.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class OpenAiCompatClientParseTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenAiCompatClient client = new OpenAiCompatClient("http://localhost:1/v1", "sk-test",
            java.time.Duration.ofSeconds(5), mapper);

    @Test
    void 正常响应_提取内容模型与Token用量() {
        String body = """
                {"id":"chatcmpl-1","object":"chat.completion","model":"gpt-4o-mini","choices":[
                  {"index":0,"message":{"role":"assistant","content":"生成结果文本"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":120,"completion_tokens":45,"total_tokens":165}}
                """;
        LlmChatResult r = client.parse(body, "openai", "gpt-4o-mini", 88L);
        assertEquals("生成结果文本", r.content());
        assertEquals("openai", r.provider());
        assertEquals("gpt-4o-mini", r.model());
        assertEquals(120, r.promptTokens());
        assertEquals(45, r.completionTokens());
        assertEquals(88L, r.latencyMs());
    }

    @Test
    void 空choices_内容空兜底不报错() {
        String body = "{\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}";
        LlmChatResult r = client.parse(body, "openai", "m", 1L);
        assertEquals("", r.content());
        assertEquals(1, r.promptTokens());
        assertEquals(1, r.completionTokens());
    }

    // ------------------------------------------------------------------
    // (prefix stabilization / prompt-cache observability): the
    // provider reports reused prompt tokens under
    // usage.prompt_tokens_details.cached_tokens (OpenAI-compatible shape).
    // The parse face must surface it so the usage-calibration chain can show
    // the two-state difference; a response without the block honestly reads 0.
    // ------------------------------------------------------------------

    @Test
    void parseSurfacesCachedTokensFromPromptTokenDetails() {
        String body = """
                {"model":"deepseek-chat","choices":[
                  {"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":1200,"completion_tokens":30,
                          "prompt_tokens_details":{"cached_tokens":1024}}}
                """;
        LlmChatResult r = client.parse(body, "deepseek", "deepseek-chat", 40L);
        assertEquals(1200, r.promptTokens());
        assertEquals(1024, r.cachedTokens());
    }

    @Test
    void parseWithoutCacheDetailsReadsZeroCachedTokens() {
        String body = """
                {"model":"gpt-4o-mini","choices":[
                  {"index":0,"message":{"role":"assistant","content":"ok"}}],
                 "usage":{"prompt_tokens":10,"completion_tokens":2}}
                """;
        LlmChatResult r = client.parse(body, "openai", "gpt-4o-mini", 5L);
        assertEquals(0, r.cachedTokens());
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Test
    void tool_calls响应_解析调用清单() {
        String body = """
                {"model":"gpt-4o-mini","choices":[
                  {"index":0,"finish_reason":"tool_calls",
                   "message":{"role":"assistant","content":null,"tool_calls":[
                     {"id":"call_1","type":"function",
                      "function":{"name":"kb_search","arguments":"{\\"query\\":\\"登录\\"}"}}]}}],
                 "usage":{"prompt_tokens":80,"completion_tokens":12}}
                """;
        LlmChatResult r = client.parse(body, "openai", "gpt-4o-mini", 5L);
        assertEquals(1, r.toolCalls().size());
        assertEquals("call_1", r.toolCalls().get(0).id());
        assertEquals("kb_search", r.toolCalls().get(0).name());
        assertEquals("{\"query\":\"登录\"}", r.toolCalls().get(0).argumentsJson());
        assertEquals("", r.content());
        assertEquals(80, r.promptTokens());
    }

    @Test
    void 多tool_calls_按响应序全量保留() {
        String body = """
                {"choices":[{"message":{"role":"assistant","tool_calls":[
                  {"id":"a","function":{"name":"t1","arguments":"{}"}},
                  {"id":"b","function":{"name":"t2","arguments":"{\\"x\\":1}"}}]}}]}
                """;
        LlmChatResult r = client.parse(body, "openai", "m", 1L);
        assertEquals(2, r.toolCalls().size());
        assertEquals("t1", r.toolCalls().get(0).name());
        assertEquals("t2", r.toolCalls().get(1).name());
    }

    @Test
    void 无tool_calls字段_toolCalls为null消费侧免判空集() {
        String body = "{\"choices\":[{\"message\":{\"content\":\"纯文本\"}}]}";
        LlmChatResult r = client.parse(body, "openai", "m", 1L);
        assertEquals(null, r.toolCalls());
        assertEquals("纯文本", r.content());
    }

    @Test
    void tool_calls空数组_解析空清单() {
        String body = "{\"choices\":[{\"message\":{\"content\":\"x\",\"tool_calls\":[]}}]}";
        LlmChatResult r = client.parse(body, "openai", "m", 1L);
        assertEquals(0, r.toolCalls().size());
    }

    @Test
    void 缺usage字段_按0兜底() {
        String body = "{\"choices\":[{\"message\":{\"content\":\"x\"}}]}";
        LlmChatResult r = client.parse(body, "openai", "m", 1L);
        assertEquals("x", r.content());
        assertEquals(0, r.promptTokens());
        assertEquals(0, r.completionTokens());
    }

    @Test
    void 响应非JSON_抛格式校验失败50003() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> client.parse("not-json{{{", "openai", "m", 1L));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, ex.getCode().intValue());
    }

    @Test
    void 响应缺choices字段_空内容不抛错() {
        LlmChatResult r = client.parse("{\"model\":\"m\"}", "openai", "m", 1L);
        assertEquals("", r.content());
        assertEquals("m", r.model());
        assertTrue(r.latencyMs() >= 0);
    }
}
