package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class PromptSizeGuardTest {

    private final TokenCounter counter = new TokenCounter();
    private final PromptSizeGuard guard = new PromptSizeGuard(counter);

    private static List<ChatMessage> messages(int zhChars) {
        return List.of(new ChatMessage("system", "你是助手"),
                new ChatMessage("user", "字".repeat(zhChars)));
    }

    
    @Test
    void withinLimit_直通() {
        List<ChatMessage> msgs = messages(100); // ≈75 token+4
        PromptSizeGuard.Verdict v = guard.check(msgs, 512, true);
        assertEquals(PromptSizeGuard.Decision.PASS, v.decision());
        assertTrue(v.estTokens() > 0 && v.estTokens() <= 512);
    }

    
    @Test
    void 临界两侧行为翻转() {
        List<ChatMessage> atLimit = messages(1000); // 750+4 token
        int est = guard.estimate(atLimit);
        PromptSizeGuard.Verdict pass = guard.check(atLimit, est, true);
        assertEquals(PromptSizeGuard.Decision.PASS, pass.decision());
        PromptSizeGuard.Verdict over = guard.check(messages(1002), est, true);
        assertEquals(PromptSizeGuard.Decision.COMPRESS, over.decision());
    }

    
    @Test
    void 超限分块链_判压缩不抛() {
        PromptSizeGuard.Verdict v = guard.check(messages(400000), 1024, true);
        assertEquals(PromptSizeGuard.Decision.COMPRESS, v.decision());
    }

    
    @Test
    void 超限非分块_failFast人话含数字() {
        List<ChatMessage> msgs = messages(400000);
        int estBefore = guard.estimate(msgs);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.check(msgs, 1024, false));
        assertEquals(com.sixbean.testpike.common.exception.BizErrorCode.AI_PROMPT_TOO_LARGE, ex.getCode());
        assertTrue(ex.getMessage().contains("提示词超出模型上限"), ex.getMessage());
        assertTrue(ex.getMessage().contains(String.valueOf(estBefore)),
                "报错须含实际 token 数: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(String.valueOf(1024)),
                "报错须含上限: " + ex.getMessage());
        assertEquals(estBefore, guard.estimate(msgs));
    }

    
    @Test
    void 生效预算取小() {
        assertEquals(1024, PromptSizeGuard.effectiveLimit(5000, 1024));
        assertEquals(5000, PromptSizeGuard.effectiveLimit(5000, 1048576));
        assertEquals(5000, PromptSizeGuard.effectiveLimit(5000, null));
    }

    
    @Test
    void null上限_仅文档预算生效() {
        PromptSizeGuard.Verdict v = guard.check(messages(400000), 1024, true, null);
        assertEquals(PromptSizeGuard.Decision.COMPRESS, v.decision());
    }

    
    @Test
    void estimate_用jTokkit非常量法() {
        List<ChatMessage> msgs = messages(1000);
        int exact = counter.countTexts(List.of("你是助手", "字".repeat(1000)));
        assertEquals(exact, guard.estimate(msgs));
        long constantStyle = Math.round(msgs.get(1).content().length() * 512.0 / 480);
        assertTrue(counter.countTexts(List.of(msgs.get(1).content())) < constantStyle,
                "中文下常量法必高估（方向钉，IT-CTX-04）");
    }
}
