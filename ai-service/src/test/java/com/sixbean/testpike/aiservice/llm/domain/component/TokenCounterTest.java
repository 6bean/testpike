package com.sixbean.testpike.aiservice.llm.domain.component;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class TokenCounterTest {

    private final TokenCounter counter = new TokenCounter();

    
    @Test
    void countTokens_基线文本非零且稳定() {
        assertEquals(1, counter.countTokens("字"));
        assertEquals(counter.countTokens("hello world"), counter.countTokens("hello world"));
        assertTrue(counter.countTokens("hello world") >= 2);
    }

 
    @Test
    void countTokens_纯中文段落在实测偏差带() {
        String zh = "系统应支持用户使用手机号登录，并接受短信验证码。".repeat(30);
        int tokens = counter.countTokens(zh);
        assertTrue(tokens >= zh.length() * 0.65 && tokens <= zh.length() * 0.85,
                "中文 token 数越带: " + tokens + "/" + zh.length());
    }

    
    @Test
    void 常量法对中英混排高估_方向钉死() {
        String mixed = "需求点提取功能（F2）：分块后调用 LLM 抽取 title 与验收标准。".repeat(8);
        long constEstimate = Math.round(mixed.length() * 512.0 / 480);
        assertTrue(constEstimate >= counter.countTokens(mixed),
                "常量法应高估（保守），实际反转=词表假设变化须重标定");
    }

    
    @Test
    void countMessages_等于内容求和() {
        List<String> parts = List.of("你是助手", "请摘要以下文档分块");
        long sum = parts.stream().mapToLong(counter::countTokens).sum();
        assertEquals(sum, counter.countTexts(parts));
    }

    
    @Test
    void 空输入回零() {
        assertEquals(0, counter.countTokens(null));
        assertEquals(0, counter.countTokens(""));
        assertEquals(0, counter.countTexts(List.of()));
    }
}
