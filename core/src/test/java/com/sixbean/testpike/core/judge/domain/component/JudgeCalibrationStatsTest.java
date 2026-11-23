package com.sixbean.testpike.core.judge.domain.component;

import com.sixbean.testpike.common.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class JudgeCalibrationStatsTest {

    @Test
    void spearmanPerfectAgreementAndReversal() {
        assertEquals(1.0, JudgeCalibrationStats.spearman(
                List.of(1, 2, 3), List.of(1, 2, 3)), 1e-9);
        assertEquals(-1.0, JudgeCalibrationStats.spearman(
                List.of(1, 2, 3), List.of(3, 2, 1)), 1e-9);
    }

    @Test
    void spearmanKnownValueWithoutTies() {
        assertEquals(0.9, JudgeCalibrationStats.spearman(
                List.of(1, 2, 3, 4, 5), List.of(1, 2, 4, 3, 5)), 1e-9);
    }

    @Test
    void spearmanTiesUseMidRanksNotNaiveDiffFormula() {
        assertEquals(0.0, JudgeCalibrationStats.spearman(
                List.of(1, 1, 2, 2), List.of(1, 2, 1, 2)), 1e-9);
    }

    @Test
    void spearmanConstantSeriesIsNotDeterminable() {
        double v = JudgeCalibrationStats.spearman(
                List.of(70, 70, 70), List.of(1, 2, 3));
        assertTrue(Double.isNaN(v), "全同分序列应判无定义 NaN，实际=" + v);
        assertTrue(Double.isNaN(JudgeCalibrationStats.spearman(List.of(5), List.of(5))),
                "单样本配对秩相关无定义");
    }

    @Test
    void cohenKappaPassFailTwoLevelKnownValues() {
        int threshold = 60;
        assertEquals(1.0, JudgeCalibrationStats.cohenKappaPass(
                List.of(70, 80, 30, 40), List.of(75, 85, 20, 50), threshold), 1e-9);
        assertEquals(0.0, JudgeCalibrationStats.cohenKappaPass(
                List.of(70, 80, 30, 40), List.of(75, 30, 80, 50), threshold), 1e-9);
        assertEquals(0.5, JudgeCalibrationStats.cohenKappaPass(
                List.of(80, 70, 30, 20), List.of(85, 75, 40, 80), threshold), 1e-9);
    }

    @Test
    void cohenKappaDegenerateAllSameLevelIsNotDeterminable() {
        assertTrue(Double.isNaN(JudgeCalibrationStats.cohenKappaPass(
                List.of(80, 90), List.of(70, 75), 60)));
    }

    @Test
    void mismatchedPairSourcesAreRejected() {
        assertThrows(ValidationException.class, () -> JudgeCalibrationStats.spearman(
                List.of(1, 2), List.of(1, 2, 3)));
        assertThrows(ValidationException.class, () -> JudgeCalibrationStats.cohenKappaPass(
                List.of(), List.of(), 60));
        assertThrows(ValidationException.class, () -> JudgeCalibrationStats.cohenKappaPass(
                java.util.Arrays.asList(70, 80), java.util.Arrays.asList(75, null), 60));
    }
}
