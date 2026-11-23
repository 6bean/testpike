package com.sixbean.testpike.admin.judge.api.controller;

import com.sixbean.testpike.admin.judge.api.dto.RunCostCardView;
import com.sixbean.testpike.admin.judge.api.dto.RunDiffRowView;
import com.sixbean.testpike.admin.judge.api.dto.RunDiffView;
import com.sixbean.testpike.admin.judge.api.dto.RunSampleCardPageView;
import com.sixbean.testpike.admin.judge.api.dto.RunSampleCardView;
import com.sixbean.testpike.admin.judge.infrastructure.remote.client.CoreJudgeClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class JudgeReportBridgeTest {

    private final CoreJudgeClient coreClient = mock(CoreJudgeClient.class);
    private final JudgeReportController controller = new JudgeReportController(coreClient);

    private static RunSampleCardPageView samplePage(long runId, int page, int size) {
        return new RunSampleCardPageView(runId, page, size, 128L, List.of(
                new RunSampleCardView(99L, 11L, "需求A（预览截断）", "用例A", "batch-export",
                        60, List.of("json_invalid:通过"), Map.of("相关性", 70.0),
                        Map.of("相关性", "偏题（offset 5-15）"), 65, null, 65, false,
                        1200, 350L)));
    }

    private static RunDiffView diffView(long a, long b, int page, int size) {
        return new RunDiffView(a, b, "pipeline", "manual", "回归集", 2, "用例生成标准",
                List.of(new RunDiffRowView(11L, "需求A", 80, 60, -20, "跌")),
                page, size, 1L, 0, 1, 0, 0, 0, 1, 80.0, 60.0, -20.0,
                Double.NaN, Double.NaN, false, "配对样本仅 1 条，显著性判不了");
    }

    
    @Test
    void sampleCardsBridgePassesThroughAndKeepsCardFieldsIntact() {
        when(coreClient.sampleCards(7L, 1, 50)).thenReturn(samplePage(7L, 1, 50));

        RunSampleCardPageView got = controller.sampleCards(7L, 1, 50).getData();

        assertEquals(128L, got.total(), "总数=core 单点口径（前端翻页锚），桥零加工");
        assertEquals(99L, got.cards().get(0).recordId(), "改判入参锚 recordId 原样回传");
        assertEquals(65, got.cards().get(0).authoritativeScore(),
                "权威分=coalesce(人工改判,机器合成)口径在 core，桥禁重算");
        assertFalse(got.cards().get(0).reviewed(), "未改判位原样透传");
        verify(coreClient).sampleCards(7L, 1, 50);
    }

    
    @Test
    void diffBridgePassesFourParamsAndKeepsInconclusiveStatsHonest() {
        when(coreClient.diffRuns(7L, 8L, 1, 50, null, null)).thenReturn(diffView(7L, 8L, 1, 50));

        RunDiffView got = controller.diffRuns(7L, 8L, 1, 50, null, null).getData();

        assertEquals("跌", got.rows().get(0).verdict(), "涨跌判定=core 单点，桥零加工");
        assertEquals(-20, got.avgDiff());
        Double.isNaN(got.welchT());
        assertFalse(got.significant(), "判不了=false 禁虚过口径不被桥翻转");
        assertEquals("配对样本仅 1 条，显著性判不了", got.significanceNote(),
                "人话注脚原样透出（用户视角解释唯一源在 core）");
        verify(coreClient).diffRuns(7L, 8L, 1, 50, null, null);
    }

    
    @Test
    void costCardBridgeKeepsUnpricedNullAmounts() {
        when(coreClient.costCard(7L)).thenReturn(new RunCostCardView(7L, 4000L, 8, 20,
                0.4, 500L, false, null, null, null, "未设置牌价键，本运行不计价"));

        RunCostCardView got = controller.costCard(7L).getData();

        assertFalse(got.priceConfigured());
        assertNull(got.estimatedCost(), "未计价 null 金额原样回传，桥禁假 0 元");
        assertEquals("未设置牌价键，本运行不计价", got.note());
        verify(coreClient).costCard(7L);
    }

    
    @Test
    void coreRejectionPropagatesWithoutRewrite() {
        when(coreClient.diffRuns(7L, 9L, 1, 50, null, null)).thenThrow(
                new ServiceException("两个运行绑定的评测集版本不同（回归集 v2 vs 回归集 v3），不可对比", 400));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.diffRuns(7L, 9L, 1, 50, null, null));
        assertEquals("两个运行绑定的评测集版本不同（回归集 v2 vs 回归集 v3），不可对比",
                ex.getMessage(), "core 人话拒原文透出，桥禁吞成 50000 或改写文案");
    }

    /** Permission gate: all three read lanes carry perm:judge:run:view. */
    @Test
    void allThreeReadsCarryTheReportDoorKey() throws Exception {
        Method cards = JudgeReportController.class.getMethod("sampleCards",
                long.class, int.class, int.class);
        Method diff = JudgeReportController.class.getMethod("diffRuns",
                long.class, long.class, int.class, int.class, String.class, String.class);
        Method cost = JudgeReportController.class.getMethod("costCard", long.class);
        assertEquals("perm:judge:run:view",
                cards.getAnnotation(RequirePermission.class).value());
        assertEquals("perm:judge:run:view",
                diff.getAnnotation(RequirePermission.class).value());
        assertEquals("perm:judge:run:view",
                cost.getAnnotation(RequirePermission.class).value());
    }
}
