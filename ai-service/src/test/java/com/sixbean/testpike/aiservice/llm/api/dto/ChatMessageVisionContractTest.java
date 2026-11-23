package com.sixbean.testpike.aiservice.llm.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ChatMessageVisionContractTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    
    @Test
    void 存量两参纯文本序列化零漂移() throws Exception {
        String plain = mapper.writeValueAsString(new ChatMessage("user", "hi"));
        assertEquals("{\"role\":\"user\",\"content\":\"hi\"}", plain);
    }

    
    @Test
    void parts态序列化为OpenAI多模态数组() throws Exception {
        ChatMessage msg = ChatMessage.userVision("失败详情原文", "http://s3/shot.png?sig=1");
        String json = mapper.writeValueAsString(msg);
        assertTrue(json.contains("\"content\":["), "content 必为数组: " + json);
        assertTrue(json.contains("{\"type\":\"text\",\"text\":\"失败详情原文\"}"), json);
        assertTrue(json.contains(
                "{\"type\":\"image_url\",\"image_url\":{\"url\":\"http://s3/shot.png?sig=1\"}}"), json);
        assertFalse(json.contains("\"parts\""), "parts 键名禁泄漏（供应商不识别）: " + json);
    }

    
    @Test
    void textContent两态统一取文() {
        ChatMessage vision = ChatMessage.userVision("甲", "http://x/1");
        assertEquals("甲", vision.textContent());
        assertEquals("hi", new ChatMessage("user", "hi").textContent());
        assertEquals("", new ChatMessage("user", null).textContent());
    }
}
