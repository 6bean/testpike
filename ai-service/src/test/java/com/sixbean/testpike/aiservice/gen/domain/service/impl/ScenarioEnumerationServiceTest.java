package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.api.dto.ScenarioEnumerationResult;
import com.sixbean.testpike.aiservice.gen.api.dto.ScenarioItem;
import com.sixbean.testpike.aiservice.gen.api.dto.ScenarioKind;
import com.sixbean.testpike.aiservice.gen.domain.component.ScenarioListParser;
import com.sixbean.testpike.aiservice.gen.domain.component.ScenarioStrategy;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage0 scenario enumeration service wiring : the enumeration
 * runs through the shared kernel deliberation ring — one LLM enumeration pass as
 * proposer, the contract parser plus the priority strategy as verifier (rejection
 * reasons and missing mandatory kinds folded back as named disputes), and a
 * targeted revision pass as reviser. L2 isolates infrastructure only: the
 * fallback chain / router / core gateway are mocks (red line: mocks allowed at
 * unit level for infrastructure), the parser and strategy are the real pure
 * pieces built in .
 */
class ScenarioEnumerationServiceTest {

    private static final long TASK_ID = 77L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final String REQUIREMENT = "用户可以使用正确的账号密码登录系统，密码长度 8-20 位";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final ScenarioEnumerationServiceImpl service = new ScenarioEnumerationServiceImpl(
            llmChain, llmRouter, coreGateway, new ScenarioListParser(new ObjectMapper()));

    /** Expansion on, cap 8, kernel rounds 2 — the shipped posture. */
    private ScenarioEnumerationServiceTest stubShippedConfig() {
        when(coreGateway.getSysConfig(ScenarioStrategy.KEY_EXPAND_ENABLED)).thenReturn("1");
        when(coreGateway.getSysConfig(ScenarioStrategy.KEY_MAX_PER_REQUIREMENT)).thenReturn("8");
        when(coreGateway.getSysConfig(
                com.sixbean.testpike.aiservice.llm.pipeline.DeliberationLoop.GOVERNANCE_KEY))
                        .thenReturn("2");
        return this;
    }

    private void stubEndpointAndReplies(String... drafts) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(com.sixbean.testpike.aiservice.support
                .GenTestFixtures.endpoint());
        LlmChatResult[] replies = new LlmChatResult[drafts.length];
        for (int i = 0; i < drafts.length; i++) {
            replies[i] = new LlmChatResult(drafts[i], "mock", "mock-model", 10, 20, 5);
        }
        when(llmChain.chat(any(ChatCompletionRequest.class), anyLong(), any())).thenReturn(
                replies[0], java.util.Arrays.copyOfRange(replies, 1, replies.length));
    }

    private static String completeDraft() {
        return "{\"scenarios\":["
                + "{\"kind\":\"main-flow\",\"title\":\"正确账号密码登录成功\",\"anchor\":\"正确的账号密码登录系统\"},"
                + "{\"kind\":\"equivalence\",\"title\":\"合法邮箱账号登录\",\"anchor\":\"账号\"},"
                + "{\"kind\":\"counter-example\",\"title\":\"错误密码被拒绝\",\"anchor\":\"正确的账号密码\"},"
                + "{\"kind\":\"boundary\",\"title\":\"密码长度取 8 与 20 边界\",\"anchor\":\"密码长度 8-20 位\"}"
                + "]}";
    }

    private static String missingCounterExampleDraft() {
        return "{\"scenarios\":["
                + "{\"kind\":\"main-flow\",\"title\":\"正确账号密码登录成功\",\"anchor\":\"正确的账号密码登录系统\"},"
                + "{\"kind\":\"equivalence\",\"title\":\"合法邮箱账号登录\",\"anchor\":\"账号\"}"
                + "]}";
    }

    @Test
    void switchOffSkipsModelCallAndKeepsLegacyShape() {
        when(coreGateway.getSysConfig(ScenarioStrategy.KEY_EXPAND_ENABLED)).thenReturn("0");
        when(coreGateway.getSysConfig(ScenarioStrategy.KEY_MAX_PER_REQUIREMENT)).thenReturn("8");

        ScenarioEnumerationResult result = service.enumerate(REQUIREMENT, 0, PROJECT_ID,
                OPERATOR_ID, TASK_ID);

        assertFalse(result.expansionEnabled());
        assertTrue(result.scenarios().isEmpty());
        verify(llmChain, never()).chat(any(ChatCompletionRequest.class), anyLong(), any());
    }

    @Test
    void p0ConvergedDraftKeepsAllKindsAndCarriesKindBriefIntoPrompt() {
        stubShippedConfig().stubEndpointAndReplies(completeDraft());

        ScenarioEnumerationResult result = service.enumerate(REQUIREMENT, 0, PROJECT_ID,
                OPERATOR_ID, TASK_ID);

        assertTrue(result.expansionEnabled());
        assertEquals(4, result.scenarios().size());
        assertTrue(result.converged());
        assertEquals(List.of(), result.residualClaims());
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain, times(1)).chat(captor.capture(), eq(PROJECT_ID), any());
        String userPrompt = captor.getValue().messages().get(1).content();
        assertTrue(userPrompt.contains("counter-example"), "P0 提议段必须带反例扩列指令");
        assertTrue(userPrompt.contains(REQUIREMENT), "提议段必须携带需求点原文");
    }

    @Test
    void p2TrimsSelectionToMainFlowAndEquivalence() {
        stubShippedConfig().stubEndpointAndReplies(completeDraft());

        ScenarioEnumerationResult result = service.enumerate(REQUIREMENT, 2, PROJECT_ID,
                OPERATOR_ID, TASK_ID);

        assertEquals(2, result.scenarios().size());
        assertTrue(result.scenarios().stream()
                .allMatch(i -> i.kind() == ScenarioKind.MAIN_FLOW || i.kind() == ScenarioKind.EQUIVALENCE));
    }

    @Test
    void missingMandatoryKindsFoldBackAsNamedDisputesIntoRevision() {
        stubShippedConfig().stubEndpointAndReplies(missingCounterExampleDraft(), completeDraft());

        ScenarioEnumerationResult result = service.enumerate(REQUIREMENT, 0, PROJECT_ID,
                OPERATOR_ID, TASK_ID);

        assertTrue(result.converged());
        assertEquals(4, result.scenarios().size());
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain, times(2)).chat(captor.capture(), eq(PROJECT_ID), any());
        String revisionPrompt = captor.getAllValues().get(1).messages().get(1).content();
        assertTrue(revisionPrompt.contains("counter-example"),
                "定向修订段必须点名缺失的场景类型，实际=" + revisionPrompt);
        assertTrue(revisionPrompt.contains("boundary"),
                "定向修订段必须点名缺失的场景类型，实际=" + revisionPrompt);
    }

    @Test
    void capRowTrimsSelectionPreview() {
        stubShippedConfig();
        when(coreGateway.getSysConfig(ScenarioStrategy.KEY_MAX_PER_REQUIREMENT)).thenReturn("2");
        stubEndpointAndReplies(completeDraft());

        ScenarioEnumerationResult result = service.enumerate(REQUIREMENT, 0, PROJECT_ID,
                OPERATOR_ID, TASK_ID);

        assertEquals(2, result.scenarios().size());
    }

    @Test
    void unparseableDraftAfterRoundsFailsWithNamedReason() {
        stubShippedConfig().stubEndpointAndReplies("这不是清单", "还不是清单");

        ServiceException rejected = assertThrows(ServiceException.class,
                () -> service.enumerate(REQUIREMENT, 0, PROJECT_ID, OPERATOR_ID, TASK_ID));

        assertTrue(rejected.getMessage().contains("场景"),
                "失败必须点名场景清单拒因，实际=" + rejected.getMessage());
    }

    @Test
    void governanceRoundCountIsReadFreshPerRun() {
        stubShippedConfig().stubEndpointAndReplies(completeDraft());

        service.enumerate(REQUIREMENT, 0, PROJECT_ID, OPERATOR_ID, TASK_ID);

        verify(coreGateway).getSysConfig(
                com.sixbean.testpike.aiservice.llm.pipeline.DeliberationLoop.GOVERNANCE_KEY);
        verify(coreGateway).getSysConfig(ScenarioStrategy.KEY_EXPAND_ENABLED);
        verify(coreGateway).getSysConfig(ScenarioStrategy.KEY_MAX_PER_REQUIREMENT);
    }
}
