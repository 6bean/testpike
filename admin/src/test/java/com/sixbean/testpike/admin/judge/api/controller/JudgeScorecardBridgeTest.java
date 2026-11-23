package com.sixbean.testpike.admin.judge.api.controller;

import com.sixbean.testpike.admin.judge.api.dto.GenerationSessionCardView;
import com.sixbean.testpike.admin.judge.api.dto.RunScorecardView;
import com.sixbean.testpike.admin.judge.api.dto.ScorecardTrendPointView;
import com.sixbean.testpike.admin.judge.infrastructure.remote.client.CoreJudgeClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class JudgeScorecardBridgeTest {

    private final CoreJudgeClient coreClient = mock(CoreJudgeClient.class);
    private final JudgeReportController controller = new JudgeReportController(coreClient);

    private static RunScorecardView scorecard(long runId) {
        return new RunScorecardView(runId, "用例生成标准", "case-generation", 3, "回归集", 2,
                2, 70, 128, 120, 76, 95, 0.7917, 240000L, 850.5,
                Map.of("相关性", 74.25, "完整性", 71.8));
    }

    
    @Test
    void scorecardBridgePassesThroughWithoutRecalculation() {
        when(coreClient.scorecard(7L)).thenReturn(scorecard(7L));

        RunScorecardView got = controller.scorecard(7L).getData();

        assertEquals(2, got.status(), "运行状态位原样回传（0待跑/1跑批中/2完成/3失败）");
        assertEquals(3, got.rubricVersion(), "钉住标准版本=Run 快照位原样透传");
        assertEquals(70, got.passScore(), "达线阈值=Run 冻结快照值，桥禁按当前标准重取");
        assertEquals(76, got.avgFinalScore(), "权威分均值=core coalesce 口径，桥零重算");
        assertEquals(74.25, got.dimensionAverages().get("相关性"), "维度均分 Map 完整回显");
        verify(coreClient).scorecard(7L);
    }

    
    @Test
    void trendBridgePassesThreeParamsAndKeepsPerPointAttribution() {
        when(coreClient.trend("用例生成标准", "case-generation", 30)).thenReturn(List.of(
                new ScorecardTrendPointView(7L, 2, "回归集", 1, "2026-10-01 10:00:00",
                        100, 71, 0.72, 200000L),
                new ScorecardTrendPointView(9L, 3, "回归集", 2, "2026-10-05 12:00:00",
                        120, 76, 0.7917, 240000L)));

        List<ScorecardTrendPointView> got =
                controller.scorecardTrend("用例生成标准", "case-generation", 30).getData();

        assertEquals(2, got.size(), "标准族切换竖线锚=rubricVersion 逐点回传，桥零折叠");
        assertEquals(3, got.get(1).rubricVersion());
        assertEquals(2, got.get(1).evalsetVersion(), "换题=新版本归因位原样");
        verify(coreClient).trend("用例生成标准", "case-generation", 30);
    }

    
    @Test
    void sessionCardsBridgeKeepsDegradedAttributionAndNullCostHonest() {
        when(coreClient.sessionCards("用例生成标准", "case-generation", 50)).thenReturn(List.of(
                new GenerationSessionCardView(9L, "pipeline", "gen-config:42", "glm/glm-4-plus",
                        "2026-10-05 12:00:00", 0, null, 0, null, 0L, null, null)));

        List<GenerationSessionCardView> got =
                controller.scorecardSessionCards("用例生成标准", "case-generation", 50).getData();

        assertEquals("gen-config:42", got.get(0).targetRef(), "归因位原样透传，桥禁二次解析");
        assertNull(got.get(0).tokensPerSample(), "零落分单条成本 null 原样，桥禁假 0");
        assertNull(got.get(0).avgFinalScore(), "无落分均值 null 不被桥补 0 分假绿");
        verify(coreClient).sessionCards("用例生成标准", "case-generation", 50);
    }

    
    @Test
    void coreRejectionPropagatesWithoutRewrite() {
        when(coreClient.scorecard(404L)).thenThrow(
                new ServiceException("评测运行不存在（id=404）", 404));

        ServiceException ex = assertThrows(ServiceException.class, () -> controller.scorecard(404L));
        assertEquals("评测运行不存在（id=404）", ex.getMessage(), "core 人话拒原文透出");
    }

    /** Permission gate: all three read lanes carry perm:judge:run:view. */
    @Test
    void allThreeReadsCarryTheReportDoorKey() throws Exception {
        Method card = JudgeReportController.class.getMethod("scorecard", long.class);
        Method trend = JudgeReportController.class.getMethod("scorecardTrend",
                String.class, String.class, int.class);
        Method session = JudgeReportController.class.getMethod("scorecardSessionCards",
                String.class, String.class, int.class);
        assertEquals("perm:judge:run:view",
                card.getAnnotation(RequirePermission.class).value());
        assertEquals("perm:judge:run:view",
                trend.getAnnotation(RequirePermission.class).value());
        assertEquals("perm:judge:run:view",
                session.getAnnotation(RequirePermission.class).value());
    }
}
