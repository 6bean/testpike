package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.domain.component.CaseJsonParser;
import com.sixbean.testpike.aiservice.gen.domain.component.CoverageChecker;
import com.sixbean.testpike.aiservice.gen.domain.component.OriginalTextBlockAssembler;
import com.sixbean.testpike.aiservice.gen.domain.component.PriorityRule;
import com.sixbean.testpike.aiservice.gen.domain.component.RuleItemExtractor;
import com.sixbean.testpike.aiservice.gen.domain.component.RuleLedgerReconciler;
import com.sixbean.testpike.aiservice.gen.domain.component.ScriptLimitResolver;
import com.sixbean.testpike.aiservice.gen.domain.component.SelfCheckValidator;
import com.sixbean.testpike.aiservice.gen.domain.component.SemanticMatcher;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * segment B-2 (service seam): the rule-ledger reconciliation rides the
 * existing stage-1 deliberation ring's feedback slot — uncovered table-row
 * rules become named disputes for the revision call, never a second loop.
 * The zero-drift contract: with the hot switch {@code
 * ai.gen.rule-ledger.enabled} absent or false (factory shape) the outbound
 * stage-1 prompts stay byte-for-byte the legacy shape, the ledger pulls no
 * chunks of its own and costs zero embedding round-trips.
 * <p>L2 isolates infrastructure only (CoreGateway HTTP plane, LLM chain and
 * project router are mocks per the project testing law); the reconciler,
 * extractor and assembler are the real stateless pieces, the similarity
 * source is mocked at the matcher seam so verdicts are deterministic.
 */
class AiGenRuleLedgerWiringTest {

    private static final long TASK_ID = 73L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long REQUIREMENT_ID = 68L;
    private static final long DOCUMENT_ID = 7L;
    private static final long NEW_CASE_ID = 902L;

    private record Rig(CoreGateway gateway, LlmFallbackChain chain,
                       SemanticMatcher matcher, AiGenServiceImpl service) {
    }

    /** One standard-header markdown rule table per chunk (the shape the
     *  extractor's gold fixtures use: header + separator + one data row)
     *  so the ledger yields exactly two TABLE_ROW items with stable
     *  criterion texts. */
    private static List<Map<String, Object>> ruleTableChunks() {
        String header = "| 字段 | 输入类型 | 必填 | 限制 |\n| --- | --- | --- | --- |\n";
        String rowA = header + "| 品牌编码 | 文本输入 | 必填 | 最长 10 个字符 |";
        String rowB = header + "| 主营品类 | 下拉选择 | 必填 | 至少选择一个品类 |";
        return List.of(
                GenTestFixtures.chunkRow(11L, 1, "3.2 主营品牌资料新增", rowA),
                GenTestFixtures.chunkRow(12L, 2, "3.2 主营品牌资料新增", rowB));
    }

    private static Rig newRig() {
        CoreGateway gateway = mock(CoreGateway.class);
        LlmFallbackChain chain = mock(LlmFallbackChain.class);
        ProjectLlmRouter router = mock(ProjectLlmRouter.class);
        SemanticMatcher matcher = mock(SemanticMatcher.class);
        RuleLedgerReconciler reconciler =
                new RuleLedgerReconciler(new RuleItemExtractor(), matcher);
        AiGenServiceImpl service = new AiGenServiceImpl(
                chain, router, gateway, new ObjectMapper(),
                new CaseJsonParser(new ObjectMapper()), new SelfCheckValidator(),
                new CoverageChecker(), new PriorityRule(), new ActionJsonValidator(),
                new ScriptLimitResolver(gateway),
                null, null, null, null, null, null, null, null, null, null,
                new OriginalTextBlockAssembler(), null, null, null, null, null, null,
                reconciler);
        when(router.route(PROJECT_ID)).thenReturn(endpoint());
        when(gateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "reg", "zh-CN", 1));
        when(gateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-reg-902"));
        when(gateway.getRequirement(REQUIREMENT_ID)).thenReturn(
                new CoreGateway.RequirementBrief(REQUIREMENT_ID, "品牌资料需求",
                        "字段规则全部落地", new CoreGateway.SourceAnchor(
                                DOCUMENT_ID, List.of(12L), "至少选择一个品类")));
        when(gateway.getDocumentChunks(DOCUMENT_ID)).thenReturn(ruleTableChunks());
        when(chain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    // Stage-aware stub (injection-test shape): the system
                    // block names the stage, stage 2 gets a valid script.
                    String system = ((ChatCompletionRequest) inv.getArgument(0))
                            .messages().get(0).content();
                    String draft = system.contains("STAGE1_CASE_JSON")
                            ? GenTestFixtures.stage1Json() : GenTestFixtures.stage2Json();
                    return new LlmChatHandle(new LlmChatResult(draft, "openai",
                            "routed-model", 100, 50, 20L), endpoint());
                });
        return new Rig(gateway, chain, matcher, service);
    }

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG, 60000);
    }

    private static AiGenMessage message() {
        return new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1,
                "新增主营品牌资料时字段规则全部生效", REQUIREMENT_ID, null);
    }

    /** All user prompts the chain saw, in send order. */
    private static List<String> prompts(Rig rig) {
        return promptsByStage(rig, false);
    }

    /** Only the stage-1 user prompts (the system block carries the
     *  STAGE1_CASE_JSON marker) — the ledger disputes ride the stage-1
     *  revision sends, the stage-2 sends never carry them. */
    private static List<String> stage1Prompts(Rig rig) {
        return promptsByStage(rig, true);
    }

    private static List<String> promptsByStage(Rig rig, boolean stage1Only) {
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(rig.chain(), org.mockito.Mockito.atLeastOnce())
                .chatHandle(captor.capture(), eq(PROJECT_ID), any(LlmFallbackNotice.class));
        return captor.getAllValues().stream()
                .filter(r -> !stage1Only
                        || r.messages().get(0).content().contains("STAGE1_CASE_JSON"))
                .map(r -> r.messages().get(1).content()).toList();
    }

    @Test
    void enabledLedgerFeedsUncoveredRulesIntoTheRevisionSlot() {
        Rig rig = newRig();
        when(rig.gateway().getSysConfig("ai.gen.rule-ledger.enabled"))
                .thenReturn("true");
        // Row one reads as covered, row two clearly missing from the draft.
        when(rig.matcher().similarities(any(), anyString(), anyLong()))
                .thenReturn(List.of(0.95, 0.10));

        rig.service().generate(message());

        List<String> sent = stage1Prompts(rig);
        assertTrue(sent.size() >= 2,
                "a named dispute must drive at least one revision round, got " + sent.size());
        String revision = sent.get(sent.size() - 1);
        assertTrue(revision.contains("规则条目未覆盖（来源块 #12）"),
                "revision prompt must name the missing rule with its source chunk:\n" + revision);
        assertTrue(revision.contains("主营品类"), "the missing criterion is quoted verbatim");
        assertFalse(sent.get(0).contains("规则条目未覆盖"),
                "the first send stays byte-clean: disputes only ride the feedback slot");
    }

    @Test
    void absentSwitchKeepsLegacyBytesWithZeroLedgerFetchAndZeroEmbedding() {
        Rig rig = newRig();
        // Factory shape: the row is absent — the reader must fail closed to off.

        rig.service().generate(message());

        verify(rig.matcher(), never()).similarities(any(), anyString(), anyLong());
        // One legacy original-text pull only: the ledger adds no second fetch.
        verify(rig.gateway(), times(1)).getDocumentChunks(DOCUMENT_ID);
        for (String sent : prompts(rig)) {
            assertFalse(sent.contains("规则条目未覆盖"),
                    "switch off = no ledger feedback lines in any send");
        }
    }
}
