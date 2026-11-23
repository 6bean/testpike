package com.sixbean.testpike.core.judge.api.controller;

import com.sixbean.testpike.core.judge.api.dto.DispatchReceipt;
import com.sixbean.testpike.core.judge.api.dto.EvalSetBatchRaiseInput;
import com.sixbean.testpike.core.judge.api.dto.EvalSetPublishInput;
import com.sixbean.testpike.core.judge.api.dto.EvalSetRaiseInput;
import com.sixbean.testpike.core.judge.api.dto.EvalSetVersionView;
import com.sixbean.testpike.core.judge.api.dto.GenerationSessionCardView;
import com.sixbean.testpike.core.judge.api.dto.JudgeDispatchInput;
import com.sixbean.testpike.core.judge.api.dto.JudgeRuleCheckInput;
import com.sixbean.testpike.core.judge.api.dto.JudgeRuleItem;
import com.sixbean.testpike.core.judge.api.dto.RecordInput;
import com.sixbean.testpike.core.judge.api.dto.RecordView;
import com.sixbean.testpike.core.judge.api.dto.RuleVerdict;
import com.sixbean.testpike.core.judge.api.dto.RunCostCardView;
import com.sixbean.testpike.core.judge.api.dto.RunDiffRowView;
import com.sixbean.testpike.core.judge.api.dto.RunDiffView;
import com.sixbean.testpike.core.judge.api.dto.RunSampleCardPageView;
import com.sixbean.testpike.core.judge.api.dto.RunSampleCardView;
import com.sixbean.testpike.core.judge.api.dto.RunScorecardView;
import com.sixbean.testpike.core.judge.api.dto.RunView;
import com.sixbean.testpike.core.judge.api.dto.SampleInput;
import com.sixbean.testpike.core.judge.api.dto.SampleView;
import com.sixbean.testpike.core.judge.api.dto.ScorecardTrendPointView;
import com.sixbean.testpike.core.judge.domain.service.JudgeDispatchService;
import com.sixbean.testpike.core.judge.domain.service.JudgeRecordService;
import com.sixbean.testpike.core.judge.domain.service.JudgeRuleService;
import com.sixbean.testpike.core.judge.domain.service.JudgeRubricService;
import com.sixbean.testpike.core.judge.domain.service.JudgeRunService;
import com.sixbean.testpike.core.judge.domain.service.JudgeSampleService;
import com.sixbean.testpike.core.judge.domain.service.JudgeScorecardService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class JudgeInternalControllerTest {

    private final JudgeRunService runService = mock(JudgeRunService.class);
    private final JudgeSampleService sampleService = mock(JudgeSampleService.class);
    private final JudgeRecordService recordService = mock(JudgeRecordService.class);
    private final JudgeRuleService ruleService = mock(JudgeRuleService.class);
    private final JudgeDispatchService dispatchService = mock(JudgeDispatchService.class);
    private final JudgeScorecardService scorecardService = mock(JudgeScorecardService.class);
    private final JudgeRubricService rubricService = mock(JudgeRubricService.class);
    private final JudgeInternalController controller =
            new JudgeInternalController(runService, sampleService, recordService, ruleService,
                    dispatchService, scorecardService, rubricService);

    private static RunView run(long id) {
        return new RunView(id, "用例生成标准", "用例生成", 3,
                Map.of("dimensions", List.of()), "回归集", 2,
                "a".repeat(64), "pipeline", "cfg-9", 41L, 1, 9101L,
                "2026-10-09 11:00:00", null);
    }

    private static SampleView sample(long id) {
        return new SampleView(id, "回归集", 2, "a".repeat(64), "需求A", "用例A",
                List.of("参考件1"), "manual", 0, 9101L, "2026-10-09 11:00:00");
    }

    @Test
    void 运行快照回读_入参原样转服务_视图全量回显() {
        when(runService.detail(7L)).thenReturn(run(7L));
        RunView got = controller.run(7L).getData();
        assertEquals("a".repeat(64), got.evalsetDigest(), "派发对账锚原样回传（旧请求换内容拒评基准）");
        assertEquals(41L, got.llmConfigId());
        assertEquals(1, got.status());
        verify(runService).detail(7L);
    }

    @Test
    void 运行不存在_返回空视图不造二套错误语义() {
        when(runService.detail(404L)).thenReturn(null);
        assertNull(controller.run(404L).getData(), "null=消费方判空兜底口径（service 单点语义）");
    }

    @Test
    void 评测集版本样本回读_集名与版本号原样透传() {
        when(sampleService.listSamples("回归集", 2)).thenReturn(List.of(sample(11L)));
        List<SampleView> got = controller.samples("回归集", 2).getData();
        assertEquals(1, got.size());
        assertEquals("需求A", got.get(0).input());
        assertEquals(0, got.get(0).sensitive());
        verify(sampleService).listSamples("回归集", 2);
    }

    @Test
    void 训练样本提升口_入参三件原样透传_发布视图回显() {
        when(sampleService.raiseFromTrainingSamples("回归集", List.of(3L, 4L), 9101L))
                .thenReturn(new EvalSetVersionView("回归集", 3, "b".repeat(64), 2));
        EvalSetVersionView got = controller.raiseEvalSet(
                new EvalSetRaiseInput("回归集", List.of(3L, 4L), 9101L)).getData();
        assertEquals(3, got.version());
        assertEquals("b".repeat(64), got.snapshotDigest());
        assertEquals(2, got.sampleCount());
        verify(sampleService).raiseFromTrainingSamples("回归集", List.of(3L, 4L), 9101L);
    }

    @Test
    void 批次导出提升口_入参三件原样透传_发布视图回显() {
        when(sampleService.raiseFromBatchResults("回归集", List.of(7L, 8L), 9102L))
                .thenReturn(new EvalSetVersionView("回归集", 4, "c".repeat(64), 2));
        EvalSetVersionView got = controller.raiseEvalSetFromBatch(
                new EvalSetBatchRaiseInput("回归集", List.of(7L, 8L), 9102L)).getData();
        assertEquals(4, got.version());
        assertEquals("c".repeat(64), got.snapshotDigest());
        assertEquals(2, got.sampleCount());
        verify(sampleService).raiseFromBatchResults("回归集", List.of(7L, 8L), 9102L);
    }

    @Test
    void 落分_入参逐字段原样转服务_视图回显() {
        RecordInput input = new RecordInput(7L, 11L, 100,
                List.of("json-invalid:通过"), Map.of("相关性", 90.0),
                Map.of("相关性", "切题（offset 12-30）"), 350L, 1200);
        when(recordService.saveScore(any())).thenReturn(new RecordView(99L, 7L, 11L, 100,
                List.of("json-invalid:通过"), Map.of("相关性", 90.0), 94,
                Map.of("相关性", "切题（offset 12-30）"), 350L, 1200, null));

        RecordView got = controller.record(input).getData();
        assertEquals(94, got.finalScore(), "合成分=service 单点产出，本口零加工");
        assertNull(got.manualFinalScore());
        ArgumentCaptor<RecordInput> cap = ArgumentCaptor.forClass(RecordInput.class);
        verify(recordService).saveScore(cap.capture());
        assertEquals(7L, cap.getValue().runId());
        assertEquals(11L, cap.getValue().sampleId());
        assertEquals(100, cap.getValue().ruleScore());
        assertEquals(Map.of("相关性", 90.0), cap.getValue().llmScores());
        assertEquals("切题（offset 12-30）", cap.getValue().reasons().get("相关性"));
        assertEquals(350L, cap.getValue().latencyMs());
        assertEquals(1200, cap.getValue().tokens());
    }
    @Test
    void 规则层回读口_入参原样转服务_判定三字段零加工回显() {
        JudgeRuleCheckInput input = new JudgeRuleCheckInput(
                List.of(new JudgeRuleItem("json_invalid", Map.of("action", "zero"))),
                "候选产出", "基准产出");
        when(ruleService.check(input)).thenReturn(
                new RuleVerdict(0, List.of("json_invalid:产出非合法 JSON"), true));

        RuleVerdict got = controller.ruleCheck(input).getData();
        assertEquals(0, got.ruleScore(), "短路零分原样回传（本口不补默认不改写）");
        assertEquals(List.of("json_invalid:产出非合法 JSON"), got.hits());
        org.junit.jupiter.api.Assertions.assertTrue(got.shortCircuited(),
                "硬规则短路位原样回传——消费侧据此跳过 LLM 层");

        ArgumentCaptor<JudgeRuleCheckInput> cap =
                ArgumentCaptor.forClass(JudgeRuleCheckInput.class);
        verify(ruleService).check(cap.capture());
        assertEquals("json_invalid", cap.getValue().rules().get(0).checker());
        assertEquals("候选产出", cap.getValue().output());
        assertEquals("基准产出", cap.getValue().expected());
    }

    @Test
    void 派发口_runId与被评厂商原样转服务_回执零加工回显() {
        JudgeDispatchInput input = new JudgeDispatchInput(7L, "qwen");
        when(dispatchService.dispatch(7L, "qwen")).thenReturn(new DispatchReceipt(2, "glm"));

        DispatchReceipt got = controller.dispatch(input).getData();
        assertEquals(2, got.dispatched(), "入队条数=service 闸链产出，本口零加工");
        assertEquals("glm", got.judgeProvider(), "实际裁判厂商留痕=派发侧闸门链判定原样回传");

        ArgumentCaptor<String> providerCap = ArgumentCaptor.forClass(String.class);
        verify(dispatchService).dispatch(eq(7L), providerCap.capture());
        assertEquals("qwen", providerCap.getValue(), "被评厂商原样透传，本口禁改写禁补默认");
    }

    @Test
    void 派发口_被评厂商可空透传_非生成系目标语义不被本口补值() {
        JudgeDispatchInput input = new JudgeDispatchInput(8L, null);
        when(dispatchService.dispatch(8L, null)).thenReturn(new DispatchReceipt(1, "glm"));

        assertEquals(1, controller.dispatch(input).getData().dispatched());
        verify(dispatchService).dispatch(8L, null);
    }

    @Test
    void 规则层回读口_空规则透传服务_规则层不适用语义不被本口篡改() {
        JudgeRuleCheckInput input = new JudgeRuleCheckInput(List.of(), "产出", null);
        when(ruleService.check(input)).thenReturn(new RuleVerdict(null, List.of(), false));

        RuleVerdict got = controller.ruleCheck(input).getData();
        assertNull(got.ruleScore(), "ruleScore null=规则层不适用（纯 LLM 标准），本口禁补 0");
        org.junit.jupiter.api.Assertions.assertFalse(got.shortCircuited());
    }

    @Test
    void 评分卡口_原样转发服务_空值口径不被本口补零() {
        RunScorecardView card = new RunScorecardView(7L, "用例生成标准", "用例生成", 3,
                "回归集", 2, 2, 60, 10, 0, null, 0, null, 0L, null, Map.of());
        when(scorecardService.scorecard(7L)).thenReturn(card);

        RunScorecardView got = controller.scorecard(7L).getData();
        assertNull(got.avgFinalScore(), "零落分 null 口径原样回传，本口禁补 0 分假绿");
        assertNull(got.passRate());
        verify(scorecardService).scorecard(7L);
    }

    @Test
    void 趋势口_三参逐字透传_limit默认值走注解不藏服务层() {
        when(scorecardService.trend("用例生成标准", "用例生成", 50))
                .thenReturn(List.of(new ScorecardTrendPointView(7L, 3, "回归集", 2,
                        "2026-10-09 12:00:00", 5, 72, 0.6, 1200L)));

        List<ScorecardTrendPointView> got =
                controller.scorecardTrend("用例生成标准", "用例生成", 50).getData();
        assertEquals(1, got.size());
        assertEquals(72, got.get(0).avgFinalScore());
        verify(scorecardService).trend("用例生成标准", "用例生成", 50);
    }

    @Test
    void 勾选确认入库口_集名样本清单与操作人原样透传_发布视图零加工回显() {
        SampleInput qa = new SampleInput("问题原文", "参考答案",
                List.of("doc-7#offset12-30"), "ai-generated", 0);
        SampleInput mixed = new SampleInput("需求A", null, null, "manual", 1);
        EvalSetPublishInput input = new EvalSetPublishInput("AI造题集", List.of(qa, mixed), 9103L);
        when(sampleService.publish("AI造题集", List.of(qa, mixed), 9103L))
                .thenReturn(new EvalSetVersionView("AI造题集", 5, "d".repeat(64), 2));

        EvalSetVersionView got = controller.publishEvalSet(input).getData();
        assertEquals(5, got.version(), "版本递增=service 单点产出，本口零加工");
        assertEquals("d".repeat(64), got.snapshotDigest());
        assertEquals(2, got.sampleCount());
        verify(sampleService).publish("AI造题集", List.of(qa, mixed), 9103L);
    }

    @Test
    void 勾选确认入库口_空请求体人话拒_不造二套错误语义() {
        org.junit.jupiter.api.Assertions.assertThrows(
                com.sixbean.testpike.common.exception.ValidationException.class,
                () -> controller.publishEvalSet(null));
    }

    @Test
    void 战绩卡口_三参逐字透传_降级归因与空成本口径不被本口篡改() {
        when(scorecardService.sessionCards("用例生成标准", "用例生成", 50))
                .thenReturn(List.of(new GenerationSessionCardView(7L, "pipeline",
                        "gen-cfg-42", "zhipu/glm-4.6", "2026-10-09 12:00:00", 0,
                        null, 0, null, 0L, null, null)));

        List<GenerationSessionCardView> got =
                controller.sessionCards("用例生成标准", "用例生成", 50).getData();
        assertEquals(1, got.size());
        assertEquals("gen-cfg-42", got.get(0).targetRef(), "归因串原样透传禁二次解析");
        assertNull(got.get(0).tokensPerSample(), "零落分 null 成本原样回传，本口禁补 0");
        verify(scorecardService).sessionCards("用例生成标准", "用例生成", 50);
    }

    @Test
    void 样本卡片流口_三参原样转服务_页视图零加工回显() {
        RunSampleCardPageView page = new RunSampleCardPageView(7L, 2, 50, 128L,
                List.of(new RunSampleCardView(99L, 11L, "需求A", "用例A", "batch-export",
                        60, List.of("json_invalid:通过"), Map.of("相关性", 70.0),
                        Map.of("相关性", "偏题"), 65, 80, 80, true, 1200, 350L)));
        when(scorecardService.sampleCards(7L, 2, 50)).thenReturn(page);

        RunSampleCardPageView got = controller.sampleCards(7L, 2, 50).getData();
        assertEquals(128L, got.total(), "总数=service 单点口径，本口零加工");
        assertEquals(80, got.cards().get(0).authoritativeScore(),
                "权威分 coalesce 口径原样回传（本口禁重算）");
        org.junit.jupiter.api.Assertions.assertTrue(got.cards().get(0).reviewed(),
                "改判位徽标原样透传");
        verify(scorecardService).sampleCards(7L, 2, 50);
    }

    @Test
    void 两运行对比口_四参原样转服务_不可比人话拒由服务单点透出() {
        when(scorecardService.diffRuns(7L, 8L, 1, 50, null, null)).thenReturn(new RunDiffView(
                7L, 8L, "pipeline", "manual", "回归集", 2, "用例生成标准",
                List.of(new RunDiffRowView(11L, "需求A", 80, 60, -20, "跌")),
                1, 50, 1L, 0, 1, 0, 0, 0, 1, 80.0, 60.0, -20.0,
                Double.NaN, Double.NaN, false, "配对样本仅 1 条，显著性判不了"));

        RunDiffView got = controller.diffRuns(7L, 8L, 1, 50, null, null).getData();
        assertEquals("跌", got.rows().get(0).verdict(), "涨跌判定=service 单点，本口零加工");
        org.junit.jupiter.api.Assertions.assertTrue(Double.isNaN(got.welchT()),
                "NaN=判不了原样透传，本口禁补 0 假装算得出");
        org.junit.jupiter.api.Assertions.assertFalse(got.significant(), "禁虚过口径不被本口翻转");
        verify(scorecardService).diffRuns(7L, 8L, 1, 50, null, null);
    }

    @Test
    void 成本卡口_runId原样转服务_未计价空金额不被本口编造() {
        when(scorecardService.costCard(7L)).thenReturn(new RunCostCardView(7L, 4000L, 8, 20,
                0.4, 500L, false, null, null, null, "未设置牌价键，本运行不计价"));

        RunCostCardView got = controller.costCard(7L).getData();
        assertNull(got.estimatedCost(), "未计价 null 金额原样回传，本口禁编造金额");
        assertEquals("未设置牌价键，本运行不计价", got.note(), "人话注脚原样透出");
        verify(scorecardService).costCard(7L);
    }
}
