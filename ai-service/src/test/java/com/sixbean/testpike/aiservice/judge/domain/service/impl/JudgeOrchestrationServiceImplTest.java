package com.sixbean.testpike.aiservice.judge.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.judge.domain.component.EvalTargetAdapterRegistry;
import com.sixbean.testpike.aiservice.judge.domain.component.HttpEvalTargetAdapter;
import com.sixbean.testpike.aiservice.judge.domain.component.JudgeLlmVerdictParser;
import com.sixbean.testpike.aiservice.judge.domain.component.JudgePromptComposer;
import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeArtifactGateway;
import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeCoreGateway;
import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeRecordWrite;
import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeRuleVerdictBrief;
import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeRunBrief;
import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeSampleBrief;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmAuditRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmConfigBrief;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmProviderFactory;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.JudgeSampleMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class JudgeOrchestrationServiceImplTest {

    private JudgeCoreGateway judgeGateway;
    private CoreGateway coreGateway;
    private LlmProviderFactory factory;
    private JudgeArtifactGateway artifactGateway;
    private JudgeOrchestrationServiceImpl service;
    private JudgeLlmVerdictParser verdictParser;
    private JudgePromptComposer promptComposer;

    private static final JudgeSampleMessage MSG =
            new JudgeSampleMessage(5L, 11L, 77L, "abc123");

    private static final Map<String, Object> RUBRIC = Map.of(
            "name", "t278j3 回归评分卡", "domain", "apitest", "version", 1,
            "dimensions", List.of(
                    Map.of("name", "相关性", "definition", "是否命中需求要点",
                            "weight", 1.0, "anchorExamples", List.of("示例锚：登录成功返回令牌")),
                    Map.of("name", "完整性", "definition", "要素是否齐备",
                            "weight", 1.0, "anchorExamples", List.of())),
            "rules", List.of(Map.of("checker", "json_valid", "params", Map.of())),
            "passScore", 60, "wRule", 0.4, "wLlm", 0.6);

    @BeforeEach
    void setUp() {
        judgeGateway = mock(JudgeCoreGateway.class);
        coreGateway = mock(CoreGateway.class);
        factory = mock(LlmProviderFactory.class);
        artifactGateway = mock(JudgeArtifactGateway.class);
        when(artifactGateway.upload(anyLong(), anyLong(), any()))
                .thenReturn("testpike/0/judge-artifact/202610/abcd1234-run-5-sample-11.json");
        verdictParser = new JudgeLlmVerdictParser(new ObjectMapper());
        promptComposer = new JudgePromptComposer();
        serviceWith(new EvalTargetAdapterRegistry(List.of(new HttpEvalTargetAdapter())));
    }

    
    private void serviceWith(EvalTargetAdapterRegistry registry) {
        service = new JudgeOrchestrationServiceImpl(judgeGateway, coreGateway, factory,
                artifactGateway, verdictParser, promptComposer, registry);
    }

    private JudgeRunBrief runningRun() {
        return new JudgeRunBrief(5L, "t278j3s.regression", 2, "abc123",
                77L, 1, RUBRIC, "pipeline", "gen-config-1");
    }

    private void stubRunningChain() {
        when(judgeGateway.getRun(5L)).thenReturn(runningRun());
        when(judgeGateway.listSamples("t278j3s.regression", 2)).thenReturn(List.of(
                new JudgeSampleBrief(11L, "需求A：登录返回令牌",
                        "冻结产出A：登录接口返回token", List.of("参考件R1"), 0)));
        when(judgeGateway.ruleCheck(anyList(), any(), any()))
                .thenReturn(new JudgeRuleVerdictBrief(60, List.of("json_valid:格式检查通过"), false));
        when(coreGateway.listEnabledLlmConfigs()).thenReturn(List.of(
                new LlmConfigBrief(77L, "裁判模型", "openai", "gpt-judge",
                        "k", "http://gw.local/v1", "db")));
    }

    private void stubChat(String content) {
        when(factory.chatFor(any(ChatCompletionRequest.class), any(LlmEndpointConfig.class)))
                .thenReturn(new LlmChatResult(content, "openai", "gpt-judge", 100, 20, 999L));
    }

    
    private static String verdictJson(String anchor1, String anchor2) {
        return "{\"dimensions\":["
                + "{\"name\":\"相关性\",\"score\":85.0,\"reason\":\"命中需求要点\",\"anchor\":\""
                + anchor1 + "\"},"
                + "{\"name\":\"完整性\",\"score\":70.0,\"reason\":\"要素基本齐备\",\"anchor\":\""
                + anchor2 + "\"}]}";
    }

    @Test
    void staleRunMissing_silentlyDiscardedNoWritesNoThrow() {
        when(judgeGateway.getRun(anyLong())).thenReturn(null);
        service.judge(MSG);
        verify(judgeGateway, never()).writeRecord(any());
        verify(coreGateway, never()).writeAudit(any());
        verify(factory, never()).chatFor(any(), any());
    }

    @Test
    void digestMismatch_failClosedAuditRejectZeroLlmZeroRecord() {
        when(judgeGateway.getRun(5L)).thenReturn(new JudgeRunBrief(
                5L, "t278j3s.regression", 2, "OLD-DIGEST-被替换", 77L, 1, RUBRIC,
                "pipeline", null));
        service.judge(MSG);
        ArgumentCaptor<LlmAuditRequest> audit = ArgumentCaptor.forClass(LlmAuditRequest.class);
        verify(coreGateway).writeAudit(audit.capture());
        assertEquals("JUDGE_STALE_REJECT", audit.getValue().action());
        assertTrue(audit.getValue().objectId().contains("run:5"), audit.getValue().objectId());
        verify(judgeGateway, never()).writeRecord(any());
        verify(factory, never()).chatFor(any(), any());
    }

    @Test
    void runStatusNotRunning_staleDiscardAuditTrail() {
        when(judgeGateway.getRun(5L)).thenReturn(new JudgeRunBrief(
                5L, "t278j3s.regression", 2, "abc123", 77L, 2, RUBRIC, "pipeline", null));
        service.judge(MSG);
        ArgumentCaptor<LlmAuditRequest> audit = ArgumentCaptor.forClass(LlmAuditRequest.class);
        verify(coreGateway).writeAudit(audit.capture());
        assertEquals("JUDGE_NOT_RUNNING", audit.getValue().action());
        verify(judgeGateway, never()).writeRecord(any());
        verify(factory, never()).chatFor(any(), any());
    }

    @Test
    void shortCircuitedRuleVerdict_zeroLlmDirectRecordRuleOnly() {
        when(judgeGateway.getRun(5L)).thenReturn(runningRun());
        when(judgeGateway.listSamples("t278j3s.regression", 2)).thenReturn(List.of(
                new JudgeSampleBrief(11L, "需求A", "产出A", List.of(), 0)));
        when(judgeGateway.ruleCheck(anyList(), any(), any())).thenReturn(
                new JudgeRuleVerdictBrief(0, List.of("正则必杀:出现禁用词「xxx」"), true));
        service.judge(MSG);
        verify(factory, never()).chatFor(any(), any());
        ArgumentCaptor<JudgeRecordWrite> rec = ArgumentCaptor.forClass(JudgeRecordWrite.class);
        verify(judgeGateway).writeRecord(rec.capture());
        assertEquals(0, rec.getValue().ruleScore());
        assertEquals(List.of("正则必杀:出现禁用词「xxx」"), rec.getValue().ruleHits());
        assertTrue(rec.getValue().llmScores().isEmpty(), "短路=零 LLM 不落任何 LLM 分");
        assertEquals(5L, rec.getValue().runId());
        assertEquals(11L, rec.getValue().sampleId());
    }

    @Test
    void happyPath_writesRuleAndLlmScoresWithTokensAndLatency() {
        stubRunningChain();
        stubChat(verdictJson("登录接口返回token", "参考件R1"));
        service.judge(MSG);
        ArgumentCaptor<JudgeRecordWrite> rec = ArgumentCaptor.forClass(JudgeRecordWrite.class);
        verify(judgeGateway).writeRecord(rec.capture());
        JudgeRecordWrite write = rec.getValue();
        assertEquals(60, write.ruleScore());
        assertEquals(2, write.llmScores().size());
        assertEquals(85.0, write.llmScores().get("相关性"));
        assertEquals(70.0, write.llmScores().get("完整性"));
        assertTrue(write.reasons().get("相关性").contains("命中需求要点"));
        assertEquals(120, write.tokens(), "tokens=prompt100+completion20 实耗（C5 预算口径）");
        assertTrue(write.latencyMs() >= 0, "latency=编排实测（两次调用+校验总耗时）");
    }

    @Test
    void orphanDimension_droppedFromLlmScoresSurvivingKept() {
        stubRunningChain();
        stubChat(verdictJson("从未出现在产出里的编造锚", "参考件R1"));
        service.judge(MSG);
        ArgumentCaptor<JudgeRecordWrite> rec = ArgumentCaptor.forClass(JudgeRecordWrite.class);
        verify(judgeGateway).writeRecord(rec.capture());
        assertEquals(Map.of("完整性", 70.0), rec.getValue().llmScores(),
                "裁决二：孤儿分该维不写入 llmScores，有据维照常入账");
        assertTrue(rec.getValue().reasons().keySet().contains("完整性"));
    }

    @Test
    void allOrphanAfterOneRetry_noEvidenceAuditTrailThenRecordSurvivors() {
        stubRunningChain();
        String bogus = verdictJson("编造锚一", "编造锚二");
        when(factory.chatFor(any(ChatCompletionRequest.class), any(LlmEndpointConfig.class)))
                .thenReturn(new LlmChatResult(bogus, "openai", "gpt-judge", 100, 20, 999L));
        service.judge(MSG);
        verify(factory, times(2)).chatFor(any(), any());
        ArgumentCaptor<LlmAuditRequest> audit = ArgumentCaptor.forClass(LlmAuditRequest.class);
        verify(coreGateway).writeAudit(audit.capture());
        assertEquals("JUDGE_NO_EVIDENCE", audit.getValue().action());
        ArgumentCaptor<JudgeRecordWrite> rec = ArgumentCaptor.forClass(JudgeRecordWrite.class);
        verify(judgeGateway).writeRecord(rec.capture());
        assertTrue(rec.getValue().llmScores().isEmpty());
        assertEquals(60, rec.getValue().ruleScore(), "无据留痕不吞规则层已实算分");
    }

    @Test
    void malformedJson_retriedOnceThenBubblesForContainerRetryDeadLetter() {
        stubRunningChain();
        when(factory.chatFor(any(ChatCompletionRequest.class), any(LlmEndpointConfig.class)))
                .thenReturn(new LlmChatResult("这不是JSON", "openai", "gpt-judge", 1, 1, 5L));
        assertThrows(ServiceException.class, () -> service.judge(MSG));
        verify(factory, times(1)).chatFor(any(), any());
        verify(judgeGateway, never()).writeRecord(any());
    }

    @Test
    void missingLlmConfig_humanReject() {
        when(judgeGateway.getRun(5L)).thenReturn(runningRun());
        when(judgeGateway.listSamples(eq("t278j3s.regression"), eq(2))).thenReturn(List.of(
                new JudgeSampleBrief(11L, "需求A", "产出A", List.of(), 0)));
        when(judgeGateway.ruleCheck(anyList(), any(), any()))
                .thenReturn(new JudgeRuleVerdictBrief(60, List.of(), false));
        when(coreGateway.listEnabledLlmConfigs()).thenReturn(List.of());
        assertThrows(ServiceException.class, () -> service.judge(MSG),
                "裁判模型引用无匹配启用配置=人话拒上抛进死信，禁静默降级假绿");
    }

    @Test
    void missingSampleInFrozenVersion_humanReject() {
        when(judgeGateway.getRun(5L)).thenReturn(runningRun());
        when(judgeGateway.listSamples("t278j3s.regression", 2)).thenReturn(List.of(
                new JudgeSampleBrief(99L, "别的样本", "产出X", List.of(), 0)));
        assertThrows(ServiceException.class, () -> service.judge(MSG),
                "digest 对账通过后样本仍缺席=数据异常人话拒（重试→死信），禁评错样本");
    }


    @Test
    void llmPath_原始响应工件上传_key留痕进落分理由() {
        stubRunningChain();
        stubChat(verdictJson("登录接口返回token", "参考件R1"));
        service.judge(MSG);
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(artifactGateway).upload(eq(5L), eq(11L), payload.capture());
        String json = new String(payload.getValue(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("登录接口返回token"), "工件须含裁判 LLM 原始回包正文: " + json);
        assertTrue(json.contains("gpt-judge"), "工件须含裁判模型名（归因）: " + json);
        ArgumentCaptor<JudgeRecordWrite> rec = ArgumentCaptor.forClass(JudgeRecordWrite.class);
        verify(judgeGateway).writeRecord(rec.capture());
        assertEquals("testpike/0/judge-artifact/202610/abcd1234-run-5-sample-11.json",
                rec.getValue().reasons().get("原始响应工件"),
                "落分理由带工件 key（人审回读预签名 GET 的定位锚）");
    }

    @Test
    void shortCircuit_零LLM零工件上传() {
        when(judgeGateway.getRun(5L)).thenReturn(runningRun());
        when(judgeGateway.listSamples("t278j3s.regression", 2)).thenReturn(List.of(
                new JudgeSampleBrief(11L, "需求A", "产出A", List.of(), 0)));
        when(judgeGateway.ruleCheck(anyList(), any(), any())).thenReturn(
                new JudgeRuleVerdictBrief(0, List.of("正则必杀:禁用词"), true));
        service.judge(MSG);
        verify(artifactGateway, never()).upload(anyLong(), anyLong(), any());
    }

    @Test
    void 工件上传失败_不阻断落分主链留痕缺席() {
        stubRunningChain();
        stubChat(verdictJson("登录接口返回token", "参考件R1"));
        when(artifactGateway.upload(anyLong(), anyLong(), any()))
                .thenThrow(new ServiceException("存储不可达（桩）"));
        service.judge(MSG);
        ArgumentCaptor<JudgeRecordWrite> rec = ArgumentCaptor.forClass(JudgeRecordWrite.class);
        verify(judgeGateway).writeRecord(rec.capture());
        assertEquals(2, rec.getValue().llmScores().size(),
                "工件写失败=留痕缺失但落分主链照常（解耦红线，禁假绿禁炸链）");
        assertTrue(!rec.getValue().reasons().containsKey("原始响应工件"),
                "上传失败=工件 key 不写入留痕（悬空引用比缺失更坏）");
    }


    private JudgeRunBrief externalRun() {
        return new JudgeRunBrief(5L, "t278j3s.regression", 2, "abc123",
                77L, 1, RUBRIC, "external",
                "{\"url\":\"http://target.local/ai\",\"outputJsonPath\":\"$.answer\"}");
    }

    private void stubExternalChain(StubEvalTargetAdapter adapter) {
        when(judgeGateway.getRun(5L)).thenReturn(externalRun());
        when(judgeGateway.listSamples("t278j3s.regression", 2)).thenReturn(List.of(
                new JudgeSampleBrief(11L, "需求A：登录返回令牌", "基准答案A", List.of(), 0)));
        when(judgeGateway.ruleCheck(anyList(), any(), any()))
                .thenReturn(new JudgeRuleVerdictBrief(60, List.of(), false));
        when(coreGateway.listEnabledLlmConfigs()).thenReturn(List.of(
                new LlmConfigBrief(77L, "裁判模型", "openai", "gpt-judge",
                        "k", "http://gw.local/v1", "db")));
    }

    @Test
    void pipelineTarget_零适配调用_行为与J3完全一致() {
        StubEvalTargetAdapter adapter = new StubEvalTargetAdapter("不该被调用", null);
        serviceWith(new EvalTargetAdapterRegistry(List.of(adapter)));
        stubRunningChain();
        stubChat(verdictJson("登录接口返回token", "参考件R1"));
        service.judge(MSG);
        assertEquals(0, adapter.calls, "pipeline 分流=零外呼（禁把自家冻结产出再投喂一遍）");
        verify(judgeGateway).writeRecord(any());
    }

    @Test
    void externalTarget_被测产出替换冻结面_进裁判链落分() {
        StubEvalTargetAdapter adapter =
                new StubEvalTargetAdapter("被测系统产出：登录成功返回令牌", null);
        serviceWith(new EvalTargetAdapterRegistry(List.of(adapter)));
        stubExternalChain(adapter);
        stubChat("{\"dimensions\":["
                + "{\"name\":\"相关性\",\"score\":85.0,\"reason\":\"命中\",\"anchor\":\"登录成功返回令牌\"},"
                + "{\"name\":\"完整性\",\"score\":70.0,\"reason\":\"齐备\",\"anchor\":\"登录成功返回令牌\"}]}");
        service.judge(MSG);
        assertEquals(1, adapter.calls, "external 分流=每样本恰一次投喂");
        assertEquals(5L, adapter.lastRun.id());
        assertEquals(11L, adapter.lastSample.id());
        ArgumentCaptor<ChatCompletionRequest> reqCap =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(factory).chatFor(reqCap.capture(), any(LlmEndpointConfig.class));
        String userPrompt = reqCap.getValue().messages().get(1).content();
        assertTrue(userPrompt.contains("被测系统产出：登录成功返回令牌"),
                "【被测产出】位=适配器取回文本，实际=" + userPrompt);
        ArgumentCaptor<JudgeRecordWrite> rec = ArgumentCaptor.forClass(JudgeRecordWrite.class);
        verify(judgeGateway).writeRecord(rec.capture());
        assertEquals(2, rec.getValue().llmScores().size(),
                "锚在被测产出内=有据分入账（evidenceText 须切到被测产出口径）");
    }

    @Test
    void externalTarget_适配人话拒_上抛死信零落分禁假绿() {
        StubEvalTargetAdapter adapter = new StubEvalTargetAdapter(null,
                new ServiceException("被测 AI 系统端点不可达（桩）"));
        serviceWith(new EvalTargetAdapterRegistry(List.of(adapter)));
        stubExternalChain(adapter);
        assertThrows(ServiceException.class, () -> service.judge(MSG));
        verify(judgeGateway, never()).writeRecord(any());
        verify(factory, never()).chatFor(any(), any());
    }

    @Test
    void externalTarget_registry未知适配_人话拒零落分() {
        when(judgeGateway.getRun(5L)).thenReturn(new JudgeRunBrief(5L, "s", 2, "abc123",
                77L, 1, RUBRIC, "external", "{\"adapter\":\"ws\",\"url\":\"http://x\"}"));
        when(judgeGateway.listSamples("s", 2)).thenReturn(List.of(
                new JudgeSampleBrief(11L, "需求A", "基准A", List.of(), 0)));
        serviceWith(new EvalTargetAdapterRegistry(List.of(new HttpEvalTargetAdapter())));
        assertThrows(ServiceException.class, () -> service.judge(MSG));
        verify(judgeGateway, never()).writeRecord(any());
    }
}
