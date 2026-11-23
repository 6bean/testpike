package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.domain.component.CaseJsonParser;
import com.sixbean.testpike.aiservice.gen.domain.component.ConfidenceMeasurer;
import com.sixbean.testpike.aiservice.gen.domain.component.CoverageChecker;
import com.sixbean.testpike.aiservice.gen.domain.component.FidelityGate;
import com.sixbean.testpike.aiservice.gen.domain.component.PageInspectBridge;
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
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEventStore;
import com.sixbean.testpike.common.util.ActionJsonValidator;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Shared wiring boilerplate for the stage1 generation-chain suites (R40
 * single source: the three infrastructure mocks and the fixed endpoint shape
 * were being re-declared per suite, and the identical lines tripped the
 * duplication gate as soon as one more consumer appeared).
 * <p>L2-only helpers: mocked CoreGateway/LlmFallbackChain/ProjectLlmRouter
 * stand in for infrastructure; every judgement component stays the real one.
 */
final class GenWiringHarness {

    /** Deterministic endpoint shape shared by the wiring suites. */
    static final LlmEndpointConfig ENDPOINT = new LlmEndpointConfig(
            "openai", "m", "k", "http://localhost:4000/v1",
            LlmEndpointConfig.SOURCE_CONFIG);

    private GenWiringHarness() {
    }

    static CoreGateway mockCoreGateway() {
        return mock(CoreGateway.class);
    }

    static LlmFallbackChain mockLlmChain() {
        return mock(LlmFallbackChain.class);
    }

    static ProjectLlmRouter mockLlmRouter() {
        return mock(ProjectLlmRouter.class);
    }

    /** A chat handle over the shared endpoint with the given completion text. */
    static LlmChatHandle handle(String content) {
        return new LlmChatHandle(
                new LlmChatResult(content, "openai", "m", 100, 50, 20L), ENDPOINT);
    }

    /**
     * Assemble the generation service over the mocked infrastructure with
     * only the judgement components under test wired in — every bridge slot
     * stays null (zero-injection legacy shape). Single source for the wiring
     * suites: the identical argument ladder used to repeat across suites and
     * tripped the duplication gate as soon as one more consumer appeared.
     */
    static AiGenServiceImpl assemble(CoreGateway coreGateway, LlmFallbackChain chain,
                                     ProjectLlmRouter router, FidelityGate fidelityGate,
                                     ConfidenceMeasurer confidenceMeasurer) {
        return assemble(coreGateway, chain, router, fidelityGate, confidenceMeasurer, null);
    }

    /** segment overload: page-inspect bridge slot injectable (null = no segment). */
    static AiGenServiceImpl assemble(CoreGateway coreGateway, LlmFallbackChain chain,
                                     ProjectLlmRouter router, FidelityGate fidelityGate,
                                     ConfidenceMeasurer confidenceMeasurer,
                                     PageInspectBridge pageInspectBridge) {
        return assemble(coreGateway, chain, router, fidelityGate, confidenceMeasurer,
                pageInspectBridge, null);
    }

    /** S3 overload: budget fitting slot injectable (null = NONE = legacy bytes). */
    static AiGenServiceImpl assemble(CoreGateway coreGateway, LlmFallbackChain chain,
                                     ProjectLlmRouter router, FidelityGate fidelityGate,
                                     ConfidenceMeasurer confidenceMeasurer,
                                     PageInspectBridge pageInspectBridge,
                                     com.sixbean.testpike.aiservice.llm.domain.component
                                             .LoopContextFitting loopFitting) {
        return assemble(coreGateway, chain, router, fidelityGate, confidenceMeasurer,
                pageInspectBridge, loopFitting, null);
    }

    /** WE piece C overload: page-element summary bridge slot injectable
     * (null = zero injection = legacy bytes). */
    static AiGenServiceImpl assemble(CoreGateway coreGateway, LlmFallbackChain chain,
                                     ProjectLlmRouter router, FidelityGate fidelityGate,
                                     ConfidenceMeasurer confidenceMeasurer,
                                     PageInspectBridge pageInspectBridge,
                                     com.sixbean.testpike.aiservice.llm.domain.component
                                             .LoopContextFitting loopFitting,
                                     com.sixbean.testpike.aiservice.gen.domain.component
                                             .WebElementLedgerSummaryBridge ledgerSummaryBridge) {
        return new AiGenServiceImpl(chain, router, coreGateway, new ObjectMapper(),
                new CaseJsonParser(new ObjectMapper()), new SelfCheckValidator(),
                new CoverageChecker(), new PriorityRule(), new ActionJsonValidator(),
                new ScriptLimitResolver(coreGateway),
                null, null, null, null, null, null, null, null,
                pageInspectBridge, null, null,
                fidelityGate, confidenceMeasurer, null, loopFitting, null,
                ledgerSummaryBridge);
    }

    /**
 * S5 overload for the process-ledger suites: same zero-injection
 * shape as the other assemble overloads with the steps-gen event store
 * slot injectable (R40 single source — the identical argument ladder had
 * started to re-appear per suite).
 */
    static AiGenServiceImpl assembleWithLedger(CoreGateway coreGateway, LlmFallbackChain chain,
                                               ProjectLlmRouter router,
                                               TaskProcessEventStore store) {
        return new AiGenServiceImpl(chain, router, coreGateway, new ObjectMapper(),
                new CaseJsonParser(new ObjectMapper()), new SelfCheckValidator(),
                new CoverageChecker(), new PriorityRule(), new ActionJsonValidator(),
                new ScriptLimitResolver(coreGateway),
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                store);
    }

    /**
     * Prompt-adoption overload: real template resolver plus review-adopted
     * wording components over the same mocked infrastructure (R40 single
     * source — the wording suite and the risk-ledger suite both ride this
     * exact argument ladder; a copy per suite tripped the duplication gate).
     */
    static AiGenServiceImpl assembleWithPromptAdoption(CoreGateway coreGateway,
                                                       LlmFallbackChain chain,
                                                       ProjectLlmRouter router) {
        return new AiGenServiceImpl(chain, router, coreGateway, new ObjectMapper(),
                new CaseJsonParser(new ObjectMapper()), new SelfCheckValidator(),
                new CoverageChecker(), new PriorityRule(), new ActionJsonValidator(),
                new ScriptLimitResolver(coreGateway), null, null, null, null, null, null,
                new com.sixbean.testpike.aiservice.gen.domain.component.PromptTemplateResolver(
                        coreGateway),
                new com.sixbean.testpike.aiservice.gen.domain.component.PromptWordingAdoption(
                        coreGateway, new ObjectMapper()));
    }

    /**
 * Step-level milestone summaries recorded on the shared process ledger
 * ( S4/R40 single source: the capture boilerplate was duplicated in
 * the stage-2 suites as soon as a second consumer appeared).
 */
    static List<String> stepSummaries(TaskProcessEventStore store, long taskId) {
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(store, atLeastOnce()).record(eq("steps-gen"), eq(String.valueOf(taskId)),
                eq(String.valueOf(taskId)), eq("step"), summary.capture(), any());
        return summary.getAllValues();
    }

    /** Error-level milestone summaries on the same shared ledger. */
    static List<String> errorSummaries(TaskProcessEventStore store, long taskId) {
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(store, atLeastOnce()).record(eq("steps-gen"), eq(String.valueOf(taskId)),
                eq(String.valueOf(taskId)), eq("error"), summary.capture(), any());
        return summary.getAllValues();
    }

    /** Valid action JSON for the post-stage1 script pass (shared shape). */
    static final String STAGE2_CLEAN = """
            {"version":"v2","caseId":"TC-fidelity-5411","env":{"ENV":"dev","PACK":"none","DATA":"default"},
             "setup":[{"action":"goto","description":"打开登录页","target":{"url":"{{ENV:BASE}}/login"}}],
             "steps":[
               {"action":"wait","description":"等待登录表单出现","target":{"css":"#username","chain":["css"]},"state":"visible"},
               {"action":"expect","description":"断言跳转首页（需人工精化）","target":{"text":"首页"},"value":"首页"}
             ]}""";

    /**
     * Script the stage1 model passes and record their prompts (single source
     * for the wiring suites): a call whose system message carries the
     * case-JSON marker is a stage1 pass and consumes the next drafted text
     * (the last draft repeats once the list is spent); any other call — the
     * stage2 script pass — answers with {@link #STAGE2_CLEAN}.
     */
    static void stubStage1Drafts(LlmFallbackChain chain, long projectId,
                                 List<String> stage1PromptsOut, String... drafts) {
        stubStageFlow(chain, projectId, stage1PromptsOut, drafts, STAGE2_CLEAN);
    }

    /**
     * Same wiring with an explicit stage-2 answer: the honest-mark clamp
     * suites place the mark on the SCRIPT draft — where the real chain puts
     * it (the marking clause rides the stage-2 prompt builder only).
     */
    static void stubStageFlow(LlmFallbackChain chain, long projectId,
                              List<String> stage1PromptsOut, String[] stage1Drafts,
                              String stage2Draft) {
        int[] idx = {0};
        when(chain.chatHandle(any(), eq(projectId), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    if (r.messages().get(0).content().contains("STAGE1_CASE_JSON")) {
                        stage1PromptsOut.add(r.messages().get(1).content());
                        String draft = idx[0] < stage1Drafts.length
                                ? stage1Drafts[idx[0]] : stage1Drafts[stage1Drafts.length - 1];
                        idx[0]++;
                        return handle(draft);
                    }
                    return handle(stage2Draft);
                });
    }

    /**
     * V615 wiring overloads: the capability ledger slot injectable on the
     * zero-injection shape (null = legacy form decisions byte-for-byte).
     */
    static AiGenServiceImpl assembleWithCapabilityLedger(
            CoreGateway coreGateway, LlmFallbackChain chain, ProjectLlmRouter router,
            com.sixbean.testpike.aiservice.llm.domain.component.LlmOutputCapabilityLedger ledger) {
        return new AiGenServiceImpl(chain, router, coreGateway, new ObjectMapper(),
                new CaseJsonParser(new ObjectMapper()), new SelfCheckValidator(),
                new CoverageChecker(), new PriorityRule(), new ActionJsonValidator(),
                new ScriptLimitResolver(coreGateway),
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                ledger, null);
    }
}
