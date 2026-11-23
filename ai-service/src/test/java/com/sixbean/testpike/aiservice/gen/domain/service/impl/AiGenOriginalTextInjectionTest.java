package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.doc.domain.component.BudgetPolicy;
import com.sixbean.testpike.aiservice.gen.domain.component.CaseJsonParser;
import com.sixbean.testpike.aiservice.gen.domain.component.CoverageChecker;
import com.sixbean.testpike.aiservice.gen.domain.component.OriginalTextBlockAssembler;
import com.sixbean.testpike.aiservice.gen.domain.component.PriorityRule;
import com.sixbean.testpike.aiservice.gen.domain.component.ScriptLimitResolver;
import com.sixbean.testpike.aiservice.gen.domain.component.SelfCheckValidator;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.support.GenTestFixtures;
import com.sixbean.testpike.common.mq.AiGenMessage;
import com.sixbean.testpike.common.util.ActionJsonValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BA batch (design 3.2): a requirement point adopted with an
 * evidence anchor feeds the generation chain an "original text" block - the
 * hit chunk plus its ±1 neighbours, clamped by BudgetPolicy and injected as
 * an isolated section of the stage-1 prompt. The zero-drift contract matters
 * most: an unanchored point, a failed chunk read or an empty pull must leave
 * the stage-1 prompt byte-for-byte identical to the pre-change shape, so
 * manual points and legacy rows keep sending exactly the old outbound bytes.
 * <p>L2 isolates infrastructure only (CoreGateway HTTP plane, LLM chain and
 * project router are mocks per the project testing law); the assembler and
 * every gen-domain component are the real stateless pieces.
 */
class AiGenOriginalTextInjectionTest {

    private static final long TASK_ID = 71L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long REQUIREMENT_ID = 66L;
    private static final long DOCUMENT_ID = 7L;
    private static final long NEW_CASE_ID = 900L;

    private record Rig(CoreGateway gateway, LlmFallbackChain chain,
                       ProjectLlmRouter router, AiGenServiceImpl service) {
    }

    private static Rig newRig() {
        CoreGateway gateway = mock(CoreGateway.class);
        LlmFallbackChain chain = mock(LlmFallbackChain.class);
        ProjectLlmRouter router = mock(ProjectLlmRouter.class);
        AiGenServiceImpl service = new AiGenServiceImpl(
                chain, router, gateway, new ObjectMapper(),
                new CaseJsonParser(new ObjectMapper()), new SelfCheckValidator(),
                new CoverageChecker(), new PriorityRule(), new ActionJsonValidator(),
                new ScriptLimitResolver(gateway), new OriginalTextBlockAssembler());
        when(router.route(PROJECT_ID)).thenReturn(endpoint());
        when(gateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "reg", "zh-CN", 1));
        when(gateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-reg-900"));
        when(chain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    String system = r.messages().get(0).content();
                    String content = system.contains("STAGE1_CASE_JSON")
                            ? stage1Json() : stage2Json();
                    return new LlmChatHandle(
                            new LlmChatResult(content, "openai", "routed-model", 100, 50, 20L),
                            endpoint());
                });
        return new Rig(gateway, chain, router, service);
    }

    /** Stage-1 user prompt sent on this rig (first chat call of the task). */
    private static String stage1Prompt(Rig rig) {
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(rig.chain(), atLeastOnce())
                .chatHandle(captor.capture(), eq(PROJECT_ID), any(LlmFallbackNotice.class));
        return captor.getAllValues().get(0).messages().get(1).content();
    }

    /** Pre- wire shape: the same requirement brief without any anchor field. */
    private static String legacyPrompt() {
        Rig rig = newRig();
        when(rig.gateway().getRequirement(REQUIREMENT_ID)).thenReturn(
                new CoreGateway.RequirementBrief(REQUIREMENT_ID, "登录需求", "登录成功跳转首页"));
        rig.service().generate(message(REQUIREMENT_ID));
        return stage1Prompt(rig);
    }

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG, 60000);
    }

    private static AiGenMessage message(Long requirementId) {
        return new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1,
                "用户可以使用正确的账号密码登录系统", requirementId, null);
    }

    private static String stage1Json() {
        return GenTestFixtures.stage1Json();
    }

    private static String stage2Json() {
        return GenTestFixtures.stage2Json();
    }

    @Test
    void anchoredRequirementInjectsOriginalTextBlock() {
        Rig rig = newRig();
        when(rig.gateway().getRequirement(REQUIREMENT_ID)).thenReturn(
                new CoreGateway.RequirementBrief(REQUIREMENT_ID, "登录需求", "登录成功跳转首页",
                        new CoreGateway.SourceAnchor(DOCUMENT_ID, List.of(12L),
                                "连续三次密码错误账号锁定十分钟")));
        when(rig.gateway().getDocumentChunks(DOCUMENT_ID)).thenReturn(GenTestFixtures.loginDocumentChunks());

        rig.service().generate(message(REQUIREMENT_ID));

        String prompt = stage1Prompt(rig);
        assertTrue(prompt.contains("<original_text>"), "isolated block open marker");
        assertTrue(prompt.contains("</original_text>"), "isolated block close marker");
        assertTrue(prompt.contains("连续三次密码错误账号锁定十分钟"), "hit chunk text injected");
        assertTrue(prompt.contains("登录页提供账号与密码两个输入框"), "previous neighbour injected");
        assertTrue(prompt.contains("登录成功后跳转首页并展示用户名"), "next neighbour injected");
        assertTrue(prompt.indexOf("登录页提供账号与密码两个输入框")
                        < prompt.indexOf("连续三次密码错误账号锁定十分钟"),
                "injection keeps ascending document order");
    }

    @Test
    void unanchoredRequirementKeepsPromptByteForByteUnchanged() {
        String legacy = legacyPrompt();
        Rig rig = newRig();
        when(rig.gateway().getRequirement(REQUIREMENT_ID)).thenReturn(
                new CoreGateway.RequirementBrief(REQUIREMENT_ID, "登录需求", "登录成功跳转首页", null));

        rig.service().generate(message(REQUIREMENT_ID));

        String prompt = stage1Prompt(rig);
        assertFalse(prompt.contains("<original_text>"), "no anchor = no isolated block");
        assertEquals(legacy, prompt, "manual point must send the exact legacy prompt bytes");
    }

    @Test
    void chunkReadFailureFallsBackToByteIdenticalPrompt() {
        String legacy = legacyPrompt();
        Rig rig = newRig();
        when(rig.gateway().getRequirement(REQUIREMENT_ID)).thenReturn(
                new CoreGateway.RequirementBrief(REQUIREMENT_ID, "登录需求", "登录成功跳转首页",
                        new CoreGateway.SourceAnchor(DOCUMENT_ID, List.of(12L), "摘录")));
        when(rig.gateway().getDocumentChunks(DOCUMENT_ID))
                .thenThrow(new IllegalStateException("core 不可达"));

        rig.service().generate(message(REQUIREMENT_ID));

        assertEquals(legacy, stage1Prompt(rig),
                "a failed original-text pull must never change or break the prompt");
    }

    @Test
    void emptyChunkPullInjectsNothingAndStaysUnchanged() {
        String legacy = legacyPrompt();
        Rig rig = newRig();
        when(rig.gateway().getRequirement(REQUIREMENT_ID)).thenReturn(
                new CoreGateway.RequirementBrief(REQUIREMENT_ID, "登录需求", "登录成功跳转首页",
                        new CoreGateway.SourceAnchor(DOCUMENT_ID, List.of(999L), null)));
        when(rig.gateway().getDocumentChunks(DOCUMENT_ID)).thenReturn(List.of());

        rig.service().generate(message(REQUIREMENT_ID));

        assertEquals(legacy, stage1Prompt(rig), "empty pull = honest zero injection");
    }

    // ---- : the injection budget reads its own K8 key, never the
    // borrowed summary envelope; a pull that yields chunks but injects
    // nothing is counted as an injection gap with an honest WARN (the R43
    // exclusion branch is covered by the happy-path tests below, which must
    // produce zero gap lines). ----

    private static List<Map<String, Object>> fatChunks() {
        StringBuilder fat = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            fat.append("密码策略说明");
        }
        return List.of(
                GenTestFixtures.chunkRow(11L, 1, "2.1 登录", fat.toString()),
                GenTestFixtures.chunkRow(12L, 2, "2.1 登录", fat.toString()),
                GenTestFixtures.chunkRow(13L, 3, "2.2 首页", fat.toString()));
    }

    /** Isolated block rendered into the stage-1 prompt ("" when absent). */
    private static String injectedBlock(String prompt) {
        int start = prompt.indexOf("<original_text>");
        int end = prompt.indexOf("</original_text>");
        return start >= 0 && end > start ? prompt.substring(start, end) : "";
    }

    private static void anchoredWith(Rig rig, List<Long> chunkIds, String evidence) {
        when(rig.gateway().getRequirement(REQUIREMENT_ID)).thenReturn(
                new CoreGateway.RequirementBrief(REQUIREMENT_ID, "登录需求", "登录成功跳转首页",
                        new CoreGateway.SourceAnchor(DOCUMENT_ID, chunkIds, evidence)));
    }

    @Test
    void injectionBudgetFollowsIndependentGenKeyNotSummaryEnvelope() {
        try (var sink = com.sixbean.testpike.aiservice.testsupport.LogbackSink
                .attach(AiGenServiceImpl.class)) {
            Rig rig = newRig();
            anchoredWith(rig, List.of(12L), null);
            when(rig.gateway().getDocumentChunks(DOCUMENT_ID)).thenReturn(fatChunks());
            when(rig.gateway().getSysConfig(
                    OriginalTextBlockAssembler.KEY_ORIGINAL_BLOCK_MAX_TOKENS))
                    .thenReturn("600");

            rig.service().generate(message(REQUIREMENT_ID));

            String block = injectedBlock(stage1Prompt(rig));
            assertFalse(block.isEmpty(), "a small injected budget must still produce the block");
            assertTrue(block.length()
                            <= OriginalTextBlockAssembler.budgetCharsOf(600) + 400,
                    "block must honour the independent key budget, not the summary envelope");
            assertTrue(block.contains("（内容已截断）"), "clamp is announced, never silent");
            // the borrowed summary envelope must never be consulted again
            verify(rig.gateway(), org.mockito.Mockito.never())
                    .getSysConfig(BudgetPolicy.KEY_SUMMARY_RATIO);
            verify(rig.gateway(), org.mockito.Mockito.never())
                    .getSysConfig(BudgetPolicy.KEY_SUMMARY_MAX_TOKENS);
            // R43 exclusion-branch pair: a successful injection is NOT a gap
            assertTrue(sink.warnsContaining("原文区块注入缺口").isEmpty(),
                    "injected block must not be counted as an injection gap");
        }
    }

    @Test
    void garbageGenKeyFailsClosedToFactoryAndNamesTheKey() {
        try (var sink = com.sixbean.testpike.aiservice.testsupport.LogbackSink
                .attach(AiGenServiceImpl.class)) {
            Rig rig = newRig();
            anchoredWith(rig, List.of(12L), null);
            when(rig.gateway().getDocumentChunks(DOCUMENT_ID))
                    .thenReturn(GenTestFixtures.loginDocumentChunks());
            when(rig.gateway().getSysConfig(
                    OriginalTextBlockAssembler.KEY_ORIGINAL_BLOCK_MAX_TOKENS))
                    .thenReturn("not-a-number");

            rig.service().generate(message(REQUIREMENT_ID));

            String block = injectedBlock(stage1Prompt(rig));
            assertTrue(block.contains("连续三次密码错误账号锁定十分钟"),
                    "garbage raw value must degrade to the factory 8000-token budget, never zero");
            assertFalse(sink.warnsContaining(
                    OriginalTextBlockAssembler.KEY_ORIGINAL_BLOCK_MAX_TOKENS).isEmpty(),
                    "fail-closed must name the key in a WARN (AQ honest-pointer posture)");
        }
    }

    @Test
    void pulledChunksWithEmptyInjectionCountAsGapWarn() {
        String legacy = legacyPrompt();
        try (var sink = com.sixbean.testpike.aiservice.testsupport.LogbackSink
                .attach(AiGenServiceImpl.class)) {
            Rig rig = newRig();
            // live-incident shape (ruling 8): anchor keeps an excerpt but its
            // chunkIds no longer resolve -> 642-like pull, zero bytes injected
            anchoredWith(rig, List.of(), "文中不存在的摘录锚文本");
            when(rig.gateway().getDocumentChunks(DOCUMENT_ID))
                    .thenReturn(GenTestFixtures.loginDocumentChunks());

            rig.service().generate(message(REQUIREMENT_ID));

            String prompt = stage1Prompt(rig);
            assertEquals(legacy, prompt, "zero injection keeps the prompt bytes unchanged");
            assertFalse(sink.warnsContaining("原文区块注入缺口").isEmpty(),
                    "取块>0 且 注入=0 must raise the counted gap WARN");
            assertTrue(sink.warnsContaining("原文区块注入缺口").get(0)
                    .contains(String.valueOf(REQUIREMENT_ID)), "gap line names the requirement");
        }
    }

    // ---- : the widened chapter-aligned window is driven by the
    // reader-side key ai.gen.chapter-aligned-window.enabled (factory off).
    // On = whole-chapter siblings ride into the isolated block; absent/off
    // row = the legacy ±1 bytes stay unchanged (zero-drift contract). ----

    private static List<Map<String, Object>> brandChapterChunks() {
        return List.of(
                GenTestFixtures.chunkRow(71L, 1, "3.2 主营品牌资料新增", "字段表首块：品牌编码规则"),
                GenTestFixtures.chunkRow(72L, 2, "3.2 主营品牌资料新增", "字段表续块：品牌名称长度"),
                GenTestFixtures.chunkRow(73L, 3, "3.2 主营品牌资料新增", "校验规则：品牌编码唯一"),
                GenTestFixtures.chunkRow(74L, 4, "3.2 主营品牌资料新增", "示例：新增主营品牌操作步骤"),
                GenTestFixtures.chunkRow(75L, 5, "3.3 门店资料", "下一章节首块正文"));
    }

    @Test
    void chapterAlignedWindowKeyOnWidensInjectedBlock() {
        Rig rig = newRig();
        anchoredWith(rig, List.of(74L), null);
        when(rig.gateway().getDocumentChunks(DOCUMENT_ID)).thenReturn(brandChapterChunks());
        when(rig.gateway().getSysConfig(OriginalTextBlockAssembler.KEY_CHAPTER_ALIGNED_WINDOW))
                .thenReturn("1");

        rig.service().generate(message(REQUIREMENT_ID));

        String block = injectedBlock(stage1Prompt(rig));
        assertTrue(block.contains("字段表首块：品牌编码规则"),
                "key on = chapter head block rides into the injected window");
        assertTrue(block.contains("示例：新增主营品牌操作步骤"), "hit chunk stays injected");
    }

    @Test
    void chapterAlignedWindowKeyOffKeepsLegacyBytes() {
        Rig rig = newRig();
        anchoredWith(rig, List.of(74L), null);
        when(rig.gateway().getDocumentChunks(DOCUMENT_ID)).thenReturn(brandChapterChunks());
        when(rig.gateway().getSysConfig(OriginalTextBlockAssembler.KEY_CHAPTER_ALIGNED_WINDOW))
                .thenReturn("0");

        rig.service().generate(message(REQUIREMENT_ID));

        String block = injectedBlock(stage1Prompt(rig));
        assertFalse(block.contains("字段表首块：品牌编码规则"),
                "factory-off must never widen the window");
        assertTrue(block.contains("校验规则：品牌编码唯一"), "legacy ±1 window untouched");
    }
}
