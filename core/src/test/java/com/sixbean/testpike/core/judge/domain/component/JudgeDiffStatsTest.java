package com.sixbean.testpike.core.judge.domain.component;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class JudgeDiffStatsTest {

    private static final double EPS = 1e-4;

    @Test
    void test_welchT_matches_hand_computed_anchor() {
        double t = JudgeDiffStats.welchT(
                List.of(80, 85, 90, 75), List.of(70, 60, 65, 55));
        assertEquals(4.38174, t, EPS, "Welch t 手算锚 4.38174");
    }

    @Test
    void test_welchT_sign_flips_with_order() {
        double t = JudgeDiffStats.welchT(
                List.of(70, 60, 65, 55), List.of(80, 85, 90, 75));
        assertEquals(-4.38174, t, EPS, "两侧互换 t 变号（B-A 方向语义）");
    }

    @Test
    void test_welchT_unequal_variance_anchor() {
        double t = JudgeDiffStats.welchT(List.of(1, 2, 3, 4), List.of(10, 20, 30, 40));
        assertEquals(-3.46839, t, 1e-3, "不等方差面值锚");
    }

    @Test
    void test_welchT_undefined_returns_nan() {
        assertTrue(Double.isNaN(JudgeDiffStats.welchT(List.of(70), List.of(60, 70))),
                "单侧样本<2=判不了 NaN");
        assertTrue(Double.isNaN(JudgeDiffStats.welchT(List.of(70, 70, 70), List.of(60, 60, 60))),
                "双侧零方差 SE=0=判不了 NaN（禁除零假装无穷大显著）");
        assertEquals(0.0, JudgeDiffStats.welchT(List.of(70, 70, 70), List.of(60, 70, 80)), EPS,
                "单侧零方差正常出 t（A 均值=B 均值 → 0）");
    }

    @Test
    void test_twoSidedP_normal_approximation_anchors() {
        assertEquals(0.05, JudgeDiffStats.twoSidedPNormal(1.959964), 3e-4,
                "z=1.96 → 双侧 p≈0.05");
        assertEquals(1.0, JudgeDiffStats.twoSidedPNormal(0.0), 3e-4, "t=0 → p=1");
        assertEquals(0.0, JudgeDiffStats.twoSidedPNormal(8.0), 1e-6, "极端 t → p≈0");
        double p = JudgeDiffStats.twoSidedPNormal(4.38174);
        assertTrue(p > 0.0 && p < 0.0001, "面值锚 t=4.38174 → p 极小且非负，实际=" + p);
        assertTrue(Double.isNaN(JudgeDiffStats.twoSidedPNormal(Double.NaN)),
                "NaN 传染：判不了透传 NaN");
    }
}
