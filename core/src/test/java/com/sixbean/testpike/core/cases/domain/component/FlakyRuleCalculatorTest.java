package com.sixbean.testpike.core.cases.domain.component;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class FlakyRuleCalculatorTest {

    private final FlakyRuleCalculator calculator = new FlakyRuleCalculator();

    private static List<Integer> passes(int n) {
        return IntStream.range(0, n).mapToObj(i -> 3).toList();
    }

    private static List<Integer> fails(int n) {
        return IntStream.range(0, n).mapToObj(i -> 4).toList();
    }


    @Test
    void 翻转两次_判定flaky_理由含翻转次数与人话() {
        FlakyVerdict v = calculator.evaluate(List.of(3, 4, 3), false);
        assertTrue(v.flaky(), "2 次翻转 ≥ 阈值 2 应判 flaky");
        assertEquals(2, v.flips());
        assertEquals(3, v.samples());
        assertNotNull(v.reason(), "命中必带人话理由（hover 展示）");
        assertTrue(v.reason().contains("2 次"), "理由含翻转次数: " + v.reason());
        assertTrue(v.reason().contains("近 10 次"), "理由含窗口口径: " + v.reason());
    }

    @Test
    void 翻转一次_不判() {
        FlakyVerdict v = calculator.evaluate(List.of(3, 3, 4, 4), false);
        assertFalse(v.flaky());
        assertEquals(1, v.flips());
        assertNull(v.reason());
    }

    @Test
    void 超时计入失败侧_与通过交替即翻转() {
        FlakyVerdict v = calculator.evaluate(List.of(3, 5, 3, 5), false);
        assertTrue(v.flaky());
        assertEquals(3, v.flips());
    }

    @Test
    void 全部一致_零翻转不判() {
        assertFalse(calculator.evaluate(passes(10), false).flaky());
        assertFalse(calculator.evaluate(fails(10), false).flaky());
    }


    @Test
    void 窗口外翻转不计入_仅最近10次参与() {
        List<Integer> seq = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            seq.add(i % 2 == 0 ? 3 : 4);
        }
        seq.addAll(passes(10));
        FlakyVerdict v = calculator.evaluate(seq, false);
        assertFalse(v.flaky(), "更早的窗口外翻转不得参与判定");
        assertEquals(10, v.samples(), "样本数按窗口截断=10");
        assertEquals(0, v.flips());
    }


    @Test
    void 样本不足三次_不判即使翻转形态() {
        assertFalse(calculator.evaluate(List.of(), false).flaky(), "0 样本");
        assertFalse(calculator.evaluate(List.of(3), false).flaky(), "1 样本");
        FlakyVerdict two = calculator.evaluate(List.of(3, 4), false);
        assertFalse(two.flaky(), "2 样本=1 翻转，样本不足不判");
        assertEquals(2, two.samples());
    }


    @Test
    void 已隔离用例_最近连续5次一致_建议解除() {
        List<Integer> seq = new ArrayList<>(List.of(3, 4, 3));
        seq.addAll(passes(5));
        FlakyVerdict v = calculator.evaluate(seq, true);
        assertTrue(v.suggestClear(), "已隔离 + 最近 5 次一致 → 建议解除: seq=" + seq);
    }

    @Test
    void 未隔离用例_即使连续一致_不产生建议() {
        FlakyVerdict v = calculator.evaluate(passes(5), false);
        assertFalse(v.suggestClear(), "suggestClear 仅对当前已隔离用例有意义");
    }

    @Test
    void 已隔离用例_最近4次一致_不足5次不建议() {
        List<Integer> seq = new ArrayList<>(List.of(3, 4, 3, 4));
        seq.addAll(passes(4));
        FlakyVerdict v = calculator.evaluate(seq, true);
        assertFalse(v.suggestClear(), "连续一致 <5 次不建议");
    }

    @Test
    void 已隔离用例_最近仍翻转_维持判定理由更新() {
        FlakyVerdict v = calculator.evaluate(List.of(4, 3, 4, 3, 4), true);
        assertTrue(v.flaky());
        assertFalse(v.suggestClear());
        assertEquals(4, v.flips());
        assertNotNull(v.reason());
    }
}
