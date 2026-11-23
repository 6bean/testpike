package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * contract: the summary system prompt must keep one
 * byte-identical head across per-run budget values so the provider
 * prompt-cache prefix path can hit (same family as the prefix rule).
 * The budget digit is the only variable face and it must sit after every
 * invariant clause; the length contract itself stays mandatory and numeric
 * (E8 TC-CTX-03 semantics preserved by position, not wording).
 */
class DocExtractPromptPrefixCacheAlignmentTest {

    @Test
    void headBeforeBudgetDigitIsByteIdenticalAcrossBudgets() {
        String small = DocExtractServiceImpl.buildSummarySystemPrompt(281);
        String large = DocExtractServiceImpl.buildSummarySystemPrompt(12000);
        int cutSmall = small.indexOf("【长度契约】");
        int cutLarge = large.indexOf("【长度契约】");
        assertTrue(cutSmall > 0 && cutLarge > 0, "长度契约段必须存在");
        assertEquals(small.substring(0, cutSmall), large.substring(0, cutLarge),
                "契约段之前的固定前缀必须逐字节一致（供应商前缀缓存依赖）");
        // The stable head must be the bulk of the prompt, not a token intro.
        assertTrue(cutSmall > 80, "可缓存前缀须覆盖全部不变条款: " + cutSmall);
    }

    @Test
    void contractClauseSitsLastAndKeepsNumericBound() {
        String prompt = DocExtractServiceImpl.buildSummarySystemPrompt(281);
        assertTrue(prompt.indexOf("【长度契约】") > prompt.indexOf("要求：保留"),
                "预算数字必须排在不变条款之后（尾部），否则其右侧文本永不命中前缀缓存");
        assertTrue(prompt.indexOf("【长度契约】") > prompt.indexOf("【输入隔离】"),
                "输入隔离段属固定面，须先于预算数字");
        assertTrue(prompt.contains("不得超过 281 字"), "clamp 数字仍须显式入 prompt: " + prompt);
        assertTrue(!prompt.contains("简短"), "禁不可测措辞「简短」（E8 契约零漂移）");
    }

    @Test
    void extractionSystemPromptIsValueFreeStableHead() {
        // The extraction face carries no per-run dial before its tail, so two
        // builds under one switch state must be byte-identical (cache-warm
        // precondition asserted here instead of relying on the disk-cache tag).
        CoreGateway gateway = mock(CoreGateway.class);
        DocExtractServiceImpl probe = new DocExtractServiceImpl(
                gateway, mock(LlmFallbackChain.class), mock(ProjectLlmRouter.class),
                new ObjectMapper(),
                new com.sixbean.testpike.aiservice.llm.domain.component.TokenCounter());
        String a = probe.buildSystemPrompt();
        String b = probe.buildSystemPrompt();
        assertEquals(a, b, "同一开关态下提取 system prompt 必须字节稳定");
        assertTrue(a.indexOf("【输入隔离】") > a.indexOf("要求：title 必填"),
                "提取面的隔离段位置登记（前缀面结构锚）");
    }
}
