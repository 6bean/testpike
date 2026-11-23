package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.sixbean.testpike.common.util.LlmExtractMarkers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.aictx.domain.component.CtxMemoryHitWriter;
import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxConfigResolver;
import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxProperties;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.SkillAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.SkillUsageRemoteInput;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmPromptAssembler;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.domain.component.SkillAssemblyBridge;
import com.sixbean.testpike.aiservice.llm.domain.component.SkillUsageRecorder;
import com.sixbean.testpike.aiservice.llm.domain.component.TokenCounter;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class DocExtractServiceImplSkillWiringTest {

    private static final long DOC_ID = 5L;
    private static final long PROJECT_ID = 3L;

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "m", "k", "http://x", "config", null);
    }

    private DocExtractServiceImpl serviceWith(SkillAssemblyBridge bridge) {
        return new DocExtractServiceImpl(coreGateway, llmChain, llmRouter, new ObjectMapper(),
                new TokenCounter(), bridge);
    }

    private void stubChunksAndLlm(List<String> capturedSystems) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(List.of(
                java.util.Map.of("seq", 1, "chapter", "A", "content", "用户可登录")));
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    capturedSystems.add(r.messages().get(0).content());
                    return new LlmChatResult(
                            "{\"requirements\":[{\"title\":\"登录\",\"acceptanceCriteria\":\"AC\","
                                    + "\"chapter\":\"A\",\"confidence\":0.9}]}",
                            "openai", "m", 10, 5, 20L);
                });
    }

    @Test
    void F2提取注入role3通用SKILL留痕stage1() {
        when(coreGateway.getProjectAssembly(PROJECT_ID)).thenReturn(List.of(
                new SkillAssemblyRemoteItem(41L, "提取文风", 3, 5, 1, 1, "需求点须为可测试行为描述ZZ", 0),
                new SkillAssemblyRemoteItem(42L, "脚本规范", 1, 5, 1, 1, "CSS定位链YY", 0)));
        List<String> systems = new java.util.ArrayList<>();
        stubChunksAndLlm(systems);

        serviceWith(new SkillAssemblyBridge(coreGateway,
                new LlmPromptAssembler(new TokenCounter()),
                new SkillUsageRecorder(coreGateway, mock(CtxMemoryHitWriter.class)), new AiCtxConfigResolver(coreGateway, new AiCtxProperties())))
                .extract(new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, "tok-1"));

        String system = systems.get(0);
        assertTrue(system.contains("需求点须为可测试行为描述ZZ"), "role=3 未注入 F2 system");
        assertTrue(!system.contains("CSS定位链YY"), "role=1 脚本 SKILL 误入 F2 阶段");
        ArgumentCaptor<List<SkillUsageRemoteInput>> cap = ArgumentCaptor.forClass(List.class);
        verify(coreGateway, timeout(3000)).recordSkillUsages(cap.capture());
        SkillUsageRemoteInput usage = cap.getValue().get(0);
        assertEquals(1, usage.stage(), "F2_EXTRACT→usage.stage=1");
        assertEquals(41L, usage.skillId());
    }

    @Test
    void 旧构造零注入零破坏() {
        List<String> systems = new java.util.ArrayList<>();
        stubChunksAndLlm(systems);
        DocExtractServiceImpl legacy = new DocExtractServiceImpl(coreGateway, llmChain,
                llmRouter, new ObjectMapper(), new TokenCounter());
        legacy.extract(new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, "tok-1"));
        assertTrue(systems.get(0).contains(LlmExtractMarkers.DOC_EXTRACT_REQUIREMENTS));
        assertTrue(systems.stream().noneMatch(s -> s.contains("项目 SKILL 规范")),
                "bridge=null 不得引入注入段（T131/T132 基线零破坏）");
    }
}
