package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.domain.component.CaseJsonParser;
import com.sixbean.testpike.aiservice.gen.domain.component.CoverageChecker;
import com.sixbean.testpike.aiservice.gen.domain.component.PriorityRule;
import com.sixbean.testpike.aiservice.gen.domain.component.ScriptLimitResolver;
import com.sixbean.testpike.aiservice.gen.domain.component.SelfCheckValidator;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeSearchItem;
import com.sixbean.testpike.aiservice.knowledge.domain.component.DdlDictBridge;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.domain.component.TokenCounter;
import com.sixbean.testpike.common.mq.AiGenMessage;
import com.sixbean.testpike.common.util.ActionJsonValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * assertion truth-source, level 2 — the DDL dictionary
 * segment decoupled from the legacy ai.sql-gen.enabled gate.
 * existing DDL_DICT injection"): with the new ai.gen.truth-source.ddl-dict
 * row on (factory default,
 * an absent row keeps it on) the stage2 prompt always carries the retrieved
 * table-structure reference, so DB-facing wording cites real table/column names
 * even while the SQL action family stays closed; the legacy gate alone still
 * drives the sql.assert/sql.query whitelist (action-availability zero drift).
 * Turning the new row off restores the exact old behaviour — the segment rides
 * the sql-gen gate only.
 * <p>L2 posture (PageInspectWiringTest same shape): mocked infrastructure,
 * real DdlDictBridge over a lambda search stub; live retrieval evidence belongs
 * to the L3/L4 windows, not here.
 */
class DdlTruthSourceInjectionTest {

    private static final long TASK_ID = 98L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long CASE_ID = 981L;
    private static final String TRUTH_KEY = "ai.gen.truth-source.ddl-dict";
    private static final String LEGACY_KEY = "ai.sql-gen.enabled";
    private static final String SECTION_ANCHOR = "表结构参考";
    private static final String DDL_ROW =
            "CREATE TABLE t_order (id bigint PRIMARY KEY, status varchar(16))";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final AtomicInteger searchCalls = new AtomicInteger();

    private static LlmEndpointConfig endpoint() {
        return GenWiringHarness.ENDPOINT;
    }

    /** Real bridge over a lambda search stub always hitting one DDL_DICT row. */
    private DdlDictBridge ddlBridge() {
        return new DdlDictBridge(request -> {
            searchCalls.incrementAndGet();
            return List.of(new KnowledgeSearchItem("chunk-1", 1L, 0L, "DDL_DICT",
                    "t_order", DDL_ROW, 0.8, 0.9));
        }, coreGateway, new TokenCounter());
    }

    private AiGenServiceImpl serviceWithDdl() {
        return new AiGenServiceImpl(llmChain, llmRouter, coreGateway, new ObjectMapper(),
                new CaseJsonParser(new ObjectMapper()), new SelfCheckValidator(),
                new CoverageChecker(), new PriorityRule(), new ActionJsonValidator(),
                new ScriptLimitResolver(coreGateway),
                null, null, null, null, null, ddlBridge(),
                null, null, null);
    }

    private final List<String> stage2UserMessages = new ArrayList<>();

    private void stubBase() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getCase(CASE_ID)).thenReturn(Map.of("title", "下单落库校验用例"));
        int[] idx = {0};
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    if (!r.messages().get(0).content().contains("STAGE1_CASE_JSON")) {
                        stage2UserMessages.add(r.messages().get(1).content());
                    }
                    return new LlmChatHandle(new LlmChatResult(
                            GenWiringHarness.STAGE2_CLEAN, "openai", "m", 100, 50, 20L),
                            endpoint());
                });
    }

    /** taskType=2 rides the direct script pass — stage2 only, no stage1 drafts. */
    private String runStage2Prompt() {
        serviceWithDdl().generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 2,
                "下单后落库校验", null, CASE_ID));
        assertFalse(stage2UserMessages.isEmpty(), "stage2 提示必须产生");
        return stage2UserMessages.get(0);
    }

    @Test
    void truthGateRowAbsentKeepsLegacyClosedWhenSqlGateOff() {
        // The legacy safety net: an absent truth-source row is factory ON, but
        // this test first proves the old reader pair exists and behaves per the
        // ruling — with the truth row explicitly off and the legacy gate off,
        // nothing is retrieved and nothing is injected (pre-extension bytes).
        stubBase();
        when(coreGateway.getSysConfig(TRUTH_KEY)).thenReturn("false");

        String prompt = runStage2Prompt();

        assertFalse(prompt.contains(SECTION_ANCHOR), "双闸关=旧行为零注入:\n" + prompt);
        assertFalse(prompt.contains(DDL_ROW), "双闸关禁注入 DDL 正文:\n" + prompt);
        assertTrue(searchCalls.get() == 0, "双闸关禁发起检索");
    }

    @Test
    void truthGateDefaultOnInjectsTableStructureWhileSqlFamilyStaysClosed() {
        stubBase();
        // No stub for either key: legacy ai.sql-gen.enabled reads as absent=off
        // (int-face default-safe), the new truth row reads as absent=factory ON.

        String prompt = runStage2Prompt();

        assertTrue(prompt.contains(SECTION_ANCHOR),
                "真值闸缺行=出厂开，表结构段须注入:\n" + prompt);
        assertTrue(prompt.contains("t_order"), "注入段须含命中表名:\n" + prompt);
        assertTrue(prompt.contains(DDL_ROW), "注入段须含 DDL 正文:\n" + prompt);
        assertFalse(prompt.contains("sql.assert"),
                "SQL 族白名单仍由旧闸管辖（动作可得性零漂移）:\n" + prompt);
        assertTrue(searchCalls.get() >= 1, "出厂开须发起检索");
    }

    @Test
    void truthGateExplicitlyOffRestoresLegacyPiggyback() {
        stubBase();
        when(coreGateway.getSysConfig(TRUTH_KEY)).thenReturn("0");

        String prompt = runStage2Prompt();

        assertFalse(prompt.contains(SECTION_ANCHOR), "真值闸关+旧闸关=回旧行为:\n" + prompt);
        assertTrue(searchCalls.get() == 0, "真值闸关+旧闸关禁发起检索");
    }

    @Test
    void legacyGateOnStillInjectsWithTruthOff() {
        stubBase();
        when(coreGateway.getSysConfig(TRUTH_KEY)).thenReturn("false");
        when(coreGateway.getSysConfig(LEGACY_KEY)).thenReturn("1");

        String prompt = runStage2Prompt();

        assertTrue(prompt.contains(SECTION_ANCHOR),
                "旧闸开态注入行为不变（超集关系）:\n" + prompt);
        assertTrue(prompt.contains("sql.assert"), "旧闸开须放开 SQL 族白名单:\n" + prompt);
    }
}
