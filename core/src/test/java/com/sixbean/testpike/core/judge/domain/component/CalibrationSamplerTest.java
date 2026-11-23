package com.sixbean.testpike.core.judge.domain.component;

import com.sixbean.testpike.common.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CalibrationSamplerTest {

    private static CalibrationParams params() {
        return new CalibrationParams(0.6, 60, 200, 0.02, 0.25, 5);
    }

    private static List<ScoredSample> samples(int... scores) {
        List<ScoredSample> list = new ArrayList<>();
        for (int i = 0; i < scores.length; i++) {
            list.add(new ScoredSample((long) (i + 1), scores[i]));
        }
        return list;
    }

    @Test
    void 基线未达_全量双裁判() {
        SamplingPlan plan = CalibrationSampler.plan(samples(10, 90, 61, 59, 30),
                199, Double.NaN, params(), new Random(7));
        assertEquals(CalibrationStage.BOOTSTRAP, plan.stage());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), plan.recordIds(),
                "未建基线前一律双裁判=详设「单裁判分标参考分」前提");
    }

    @Test
    void 一致率无定义_停留bootstrap_禁误杀禁虚过() {
        SamplingPlan plan = CalibrationSampler.plan(samples(85, 40),
                300, Double.NaN, params(), new Random(7));
        assertEquals(CalibrationStage.BOOTSTRAP, plan.stage());
        assertEquals(2, plan.recordIds().size());
    }

    @Test
    void 稳态_边界全收_哨兵率为零则远端不收() {
        CalibrationParams p = new CalibrationParams(0.6, 60, 200, 0.0, 0.25, 5);
        SamplingPlan plan = CalibrationSampler.plan(samples(85, 40, 61, 56, 30),
                300, 0.8, p, new Random(7));
        assertEquals(CalibrationStage.STEADY, plan.stage());
        assertEquals(List.of(3L, 4L), plan.recordIds(),
                "±1 档内的样本才是会翻 pass/fail 的样本（decision-boundary）");
        assertEquals(2, plan.boundaryPicked());
    }

    @Test
    void 稳态_哨兵率为一则全收_同种子计划确定() {
        CalibrationParams p = new CalibrationParams(0.6, 60, 200, 1.0, 0.25, 5);
        SamplingPlan a = CalibrationSampler.plan(samples(85, 40, 61),
                300, 0.8, p, new Random(42));
        SamplingPlan b = CalibrationSampler.plan(samples(85, 40, 61),
                300, 0.8, p, new Random(42));
        assertEquals(List.of(3L, 1L, 2L), a.recordIds(), "边界优先后远端按候选序补齐");
        assertEquals(a.recordIds(), b.recordIds(), "同种子=同计划（成本与审计可复现）");
    }

    @Test
    void 滚动kappa跌破_升档用boost率弃sentinel率() {
        CalibrationParams p = new CalibrationParams(0.6, 60, 200, 1.0, 0.0, 5);
        SamplingPlan plan = CalibrationSampler.plan(samples(85, 40, 61),
                300, 0.59, p, new Random(7));
        assertEquals(CalibrationStage.BOOSTED, plan.stage());
        assertEquals(List.of(3L), plan.recordIds(), "跌破阈值=升档双裁判窗口，但补样率读 boost 键非哨兵键");
    }

    @Test
    void failClosed_缺参点名() {
        assertThrows(ValidationException.class,
                () -> CalibrationSampler.plan(null, 1, 0.9, params(), new Random(1)));
        assertThrows(ValidationException.class,
                () -> CalibrationSampler.plan(samples(10), 1, 0.9, null, new Random(1)));
        assertTrue(params().toString().contains("kappaThreshold"), "参数件字段齐全（键名对账锚）");
    }
}
