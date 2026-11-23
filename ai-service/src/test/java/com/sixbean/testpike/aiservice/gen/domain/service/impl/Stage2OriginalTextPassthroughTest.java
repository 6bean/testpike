package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * (plan A1): the stage-1 assembled original-text block rides through
 * to stage 2 as a frozen scope input — the SAME block, no second assembly
 * (R40). Two consumers of the passthrough:
 * <ul>
 * <li>the stage-2 prompt carries the isolated {@code <original_text>}
 * section so the action-JSON rewrite sees the source text;</li>
 * <li>the deliberation-ring evidence list gains the original block, so the
 * shared numeric-provenance check ({@code NumberProvenance} via
 * {@code EvidenceAnchorGate}) no longer reads figures that DO exist in
 * the source document as invented.</li>
 * </ul>
 * Zero-drift contract mirrors the stage-1 suite : no anchor / failed
 * pull / empty pull = stage-2 prompt byte-for-byte the legacy shape, and the
 * ring evidence stays the legacy list.
 * <p>L2 isolates infrastructure only (gateway/chain/router mocked per the
 * project testing law); assembler, gates and the ring are the real pieces.
 */
class Stage2OriginalTextPassthroughTest {

    private static final long TASK_ID = 88L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long REQUIREMENT_ID = 66L;
    private static final long DOCUMENT_ID = 7L;
    private static final long NEW_CASE_ID = 900L;

    private record Rig(CoreGateway gateway, LlmFallbackChain chain,
                       ProjectLlmRouter router, AiGenServiceImpl service) {
    }

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG, 60000);
    }

    private static AiGenMessage message(Long requirementId) {
        return new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1,
                "用户可以使用正确的账号密码登录系统", requirementId, null);
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
                            ? GenTestFixtures.stage1Json() : GenTestFixtures.stage2Json();
                    return new LlmChatHandle(
                            new LlmChatResult(content, "openai", "routed-model", 100, 50, 20L),
                            endpoint());
                });
        return new Rig(gateway, chain, router, service);
    }

    /** All user prompts sent on this rig's chain mock, in call order. */
    private static List<String> prompts(Rig rig) {
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(rig.chain(), atLeastOnce())
                .chatHandle(captor.capture(), eq(PROJECT_ID), any(LlmFallbackNotice.class));
        return captor.getAllValues().stream()
                .map(r -> r.messages().get(1).content())
                .toList();
    }

    /** The stage-2 action-JSON prompt (the rewrite ask, proposal round). */
    private static String stage2Prompt(Rig rig) {
        return prompts(rig).stream()
                .filter(p -> p.contains("请将以下测试用例转换为动作 JSON v2"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("rig never sent a stage-2 prompt"));
    }

    private static void anchored(Rig rig, List<Long> chunkIds, String evidence) {
        when(rig.gateway().getRequirement(REQUIREMENT_ID)).thenReturn(
                new CoreGateway.RequirementBrief(REQUIREMENT_ID, "登录需求", "登录成功跳转首页",
                        new CoreGateway.SourceAnchor(DOCUMENT_ID, chunkIds, evidence)));
    }

    /**
     * Contract 1: an anchored point injects the original block into the
     * stage-2 prompt too (same block bytes as stage 1 — hit chunk text and
     * the isolated-section markers), never a re-assembly.
     */
    @Test
    void anchoredRequirementInjectsOriginalBlockIntoStage2Prompt() {
        Rig rig = newRig();
        // evidence excerpt must survive verbatim inside its own chunk
        // (freshness guard of the assembler), else zero injection by design
        anchored(rig, List.of(12L), "连续三次密码错误账号锁定10分钟");
        when(rig.gateway().getDocumentChunks(DOCUMENT_ID)).thenReturn(List.of(
                GenTestFixtures.chunkRow(11L, 1, "2.1 登录", "登录页提供账号与密码两个输入框"),
                GenTestFixtures.chunkRow(12L, 2, "2.1 登录", "连续三次密码错误账号锁定10分钟"),
                GenTestFixtures.chunkRow(13L, 3, "2.2 首页", "登录成功后跳转首页并展示用户名")));

        rig.service().generate(message(REQUIREMENT_ID));

        String prompt = stage2Prompt(rig);
        assertTrue(prompt.contains("<original_text>"),
                "stage-2 prompt must carry the isolated original-text section; head="
                        + prompt.substring(0, Math.min(200, prompt.length())));
        assertTrue(prompt.contains("连续三次密码错误账号锁定10分钟"),
                "hit chunk text must reach the action-JSON rewrite");
        assertTrue(prompt.contains("登录成功后跳转首页并展示用户名"),
                "±1 neighbour rides the same block as stage 1");
    }

    /**
 * Contract 2 (the precision face): a draft figure sourced ONLY from the
 * original document must pass the shared numeric-provenance check once
 * the block rides the ring evidence — today the ring reads it as invented
 * and the whole order is rejected (the A1 false-positive this segment kills).
 */
    @Test
    void figureSourcedFromOriginalTextStopsBeingAnInventedNumber() {
        Rig rig = newRig();
        anchored(rig, List.of(12L), null);
        when(rig.gateway().getDocumentChunks(DOCUMENT_ID)).thenReturn(List.of(
                GenTestFixtures.chunkRow(11L, 1, "2.1 登录", "登录页提供账号与密码两个输入框"),
                GenTestFixtures.chunkRow(12L, 2, "2.1 登录", "密码长度必须为8位以上"),
                GenTestFixtures.chunkRow(13L, 3, "2.2 首页", "登录成功后跳转首页并展示用户名")));
        when(rig.chain().chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    String system = r.messages().get(0).content();
                    // assertive sentence + figure that exists only in the
                    String content = system.contains("STAGE1_CASE_JSON")
                            ? GenTestFixtures.stage1Json()
                            : "{\"version\":\"v2\",\"caseId\":\"TC-REG-001\",\"endType\":\"web\","
                              + "\"setup\":[{\"action\":\"goto\",\"target\":{\"url\":\"{{ENV:URL}}/login\"}}],"
                              + "\"steps\":[{\"action\":\"expect\",\"target\":{\"text\":"
                              + "\"结论：密码长度不足8位时提示错误\"}}],\"env\":{\"ENV\":\"dev\"}}";
                    return new LlmChatHandle(
                            new LlmChatResult(content, "openai", "routed-model", 100, 50, 20L),
                            endpoint());
                });

        rig.service().generate(message(REQUIREMENT_ID));

        // The proposal converges on the FIRST pass: no revision round carries
        // the evidence-anchor dispute. Without the passthrough the figure
        verify(rig.chain(), times(2)).chatHandle(any(), eq(PROJECT_ID),
                any(LlmFallbackNotice.class));
        assertTrue(prompts(rig).stream().noneMatch(p -> p.contains("缺少可复核锚")),
                "figure sourced from the original block must not be flagged as invented");
    }

    /**
 * Contract 3 (segment C): a sliced multi-group run keeps the block on every
 * group pass — the frozen scope carries it once, groups never re-assemble.
 */
    @Test
    void slicedGroupsAllRideTheSameFrozenOriginalBlock() {
        Rig rig = newRig();
        anchored(rig, List.of(12L), null);
        when(rig.gateway().getDocumentChunks(DOCUMENT_ID))
                .thenReturn(GenTestFixtures.loginDocumentChunks());
        // width 2 over the 3-step stage-1 skeleton = 2 groups
        when(rig.gateway().getSysConfig(ScriptLimitResolver.KEY_SLICE_STEPS))
                .thenReturn("2");

        rig.service().generate(message(REQUIREMENT_ID));

        List<String> stage2s = prompts(rig).stream()
                .filter(p -> p.contains("步骤组"))
                .toList();
        assertFalse(stage2s.isEmpty(), "rig must have run the sliced shape");
        assertTrue(stage2s.stream().allMatch(p -> p.contains("<original_text>")),
                "every group pass must see the frozen original block");
    }

    /**
     * Zero-drift contract: an unanchored point leaves the stage-2 prompt and
     * the ring evidence exactly in the legacy shape (byte-for-byte equal to
     * a run with no assembler wiring at all).
     */
    @Test
    void unanchoredRequirementKeepsStage2PromptByteForByteLegacy() {
        Rig legacy = newRig();
        when(legacy.gateway().getRequirement(REQUIREMENT_ID)).thenReturn(
                new CoreGateway.RequirementBrief(REQUIREMENT_ID, "登录需求", "登录成功跳转首页", null));
        legacy.service().generate(message(REQUIREMENT_ID));

        Rig rig = newRig();
        anchored(rig, List.of(), null);
        when(rig.gateway().getDocumentChunks(DOCUMENT_ID)).thenReturn(List.of());
        rig.service().generate(message(REQUIREMENT_ID));

        assertEquals(stage2Prompt(legacy), stage2Prompt(rig),
                "empty pull = honest zero injection, legacy stage-2 bytes hold");
        assertFalse(stage2Prompt(rig).contains("<original_text>"));
    }
}
