package com.sixbean.testpike.core.judge.domain.component;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.judge.api.dto.JudgeRuleItem;
import com.sixbean.testpike.core.judge.api.dto.RuleVerdict;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class JudgeRuleEngineTest {

    @Autowired
    private JudgeRuleEngine engine;

    private static JudgeRuleItem rule(String checker, Map<String, Object> params) {
        return new JudgeRuleItem(checker, params);
    }

    @Test
    void noRulesMeansRuleLayerNotApplicable() {
        RuleVerdict v1 = engine.run(List.of(), "任意产出", null);
        assertNull(v1.ruleScore());
        assertTrue(v1.hits().isEmpty());
        assertFalse(v1.shortCircuited());
        assertNull(engine.run(null, "任意产出", null).ruleScore());
    }

    @Test
    void allRulesPassScoresFullMarks() {
        RuleVerdict v = engine.run(List.of(
                rule("json-invalid", Map.of()),
                rule("length-range", Map.of("min", 2))),
                "{\"a\":1}", null);
        assertEquals(100, v.ruleScore());
        assertTrue(v.hits().isEmpty());
        assertFalse(v.shortCircuited());
    }

    @Test
    void emptyOutputHitsZeroScoreShortCircuit() {
        RuleVerdict v = engine.run(List.of(rule("empty-output", Map.of())), "   ", null);
        assertEquals(0, v.ruleScore());
        assertTrue(v.shortCircuited());
        assertEquals(1, v.hits().size());
        assertTrue(v.hits().get(0).startsWith("empty-output:"), "reason 须带规则名可审计：" + v.hits());
        engine.run(List.of(rule("empty-output", Map.of()),
                rule("json-invalid", Map.of())), "  ", null);
    }

    @Test
    void shortCircuitStopsLaterRulesFromRecording() {
        RuleVerdict v = engine.run(List.of(
                rule("empty-output", Map.of()),
                rule("sensitive-words", Map.of("words", List.of("身份证")))),
                "", null);
        assertEquals(1, v.hits().size(), "短路后不应记录后续检查器结果：" + v.hits());
    }

    @Test
    void jsonInvalidAndJsonArray() {
        assertTrue(hasHit(engine.run(List.of(rule("json-invalid", Map.of())), "不是JSON{", null)));
        assertFalse(hasHit(engine.run(List.of(rule("json-invalid", Map.of())), "{\"a\":1}", null)));
        assertFalse(hasHit(engine.run(List.of(rule("json-array", Map.of("min", 2))), "[1,2]", null)));
        assertTrue(hasHit(engine.run(List.of(rule("json-array", Map.of("min", 3))), "[1,2]", null)));
        assertTrue(hasHit(engine.run(List.of(rule("json-array", Map.of())), "{\"a\":1}", null)));
    }

    @Test
    void lengthRangeAndSensitiveWords() {
        assertTrue(hasHit(engine.run(List.of(rule("length-range", Map.of("min", 5))), "ab", null)));
        assertTrue(hasHit(engine.run(List.of(rule("length-range", Map.of("max", 3))), "abcdef", null)));
        assertFalse(hasHit(engine.run(List.of(rule("length-range", Map.of("min", 1, "max", 10))), "ok", null)));
        assertThrows(ValidationException.class,
                () -> engine.run(List.of(rule("length-range", Map.of())), "x", null));
        assertTrue(hasHit(engine.run(List.of(rule("sensitive-words",
                Map.of("words", List.of("身份证", "银行卡")))), "备注里写了身份证号明文", null)));
        assertFalse(hasHit(engine.run(List.of(rule("sensitive-words",
                Map.of("words", List.of("身份证")))), "干净文本", null)));
        assertThrows(ValidationException.class,
                () -> engine.run(List.of(rule("sensitive-words", Map.of())), "x", null));
    }

    @Test
    void regexForbiddenAndRequired() {
        assertTrue(hasHit(engine.run(List.of(rule("regex-forbidden",
                Map.of("pattern", "\\d{17}"))), "卡号 12345678901234567", null)));
        assertFalse(hasHit(engine.run(List.of(rule("regex-forbidden",
                Map.of("pattern", "\\d{17}"))), "普通文本", null)));
        assertTrue(hasHit(engine.run(List.of(rule("regex-required",
                Map.of("pattern", "预期结果"))), "缺少该字段的用例", null)));
        assertFalse(hasHit(engine.run(List.of(rule("regex-required",
                Map.of("pattern", "预期结果"))), "含预期结果的用例", null)));
        assertThrows(ValidationException.class,
                () -> engine.run(List.of(rule("regex-required", Map.of("pattern", "[unclosed"))), "x", null));
    }

    @Test
    void requiredSubstringsAndPlaceholderLeft() {
        assertTrue(hasHit(engine.run(List.of(rule("required-substrings",
                Map.of("items", List.of("步骤", "预期结果")))), "只有步骤", null)));
        assertFalse(hasHit(engine.run(List.of(rule("required-substrings",
                Map.of("items", List.of("步骤")))), "步骤齐全", null)));
        assertTrue(hasHit(engine.run(List.of(rule("placeholder-left", Map.of())), "点击 {{btn}} 按钮", null)));
        assertTrue(hasHit(engine.run(List.of(rule("placeholder-left", Map.of())), "标题：${项目名称}", null)));
        assertFalse(hasHit(engine.run(List.of(rule("placeholder-left", Map.of())), "正常标题文本", null)));
    }

    @Test
    void markdownFenceDuplicateLinesTruncatedTail() {
        assertTrue(hasHit(engine.run(List.of(rule("markdown-fence-leak", Map.of())),
                "```json\n{}\n```", null)));
        assertFalse(hasHit(engine.run(List.of(rule("markdown-fence-leak", Map.of())),
                "普通产出", null)));
        assertTrue(hasHit(engine.run(List.of(rule("duplicate-lines", Map.of("minDup", 3))),
                "第一步\n第一步\n第一步", null)));
        assertFalse(hasHit(engine.run(List.of(rule("duplicate-lines", Map.of())),
                "第一步\n第二步", null)));
        assertTrue(hasHit(engine.run(List.of(rule("truncated-tail", Map.of())),
                "这段产出疑似被截断了…", null)));
        assertFalse(hasHit(engine.run(List.of(rule("truncated-tail", Map.of())),
                "完整产出结尾", null)));
    }

    @Test
    void capActionClampsScoreAndKeepsRunning() {
        RuleVerdict v = engine.run(List.of(
                rule("truncated-tail", Map.of("action", "cap", "capScore", 40)),
                rule("duplicate-lines", Map.of("minDup", 2, "action", "cap", "capScore", 70))),
                "同一行…\n同一行…", null);
        assertEquals(40, v.ruleScore(), "多命中封分应取最低");
        assertFalse(v.shortCircuited());
        assertEquals(2, v.hits().size());
    }

    @Test
    void capThenZeroShortCircuitGivesZero() {
        RuleVerdict v = engine.run(List.of(
                rule("length-range", Map.of("min", 100, "action", "cap", "capScore", 50)),
                rule("sensitive-words", Map.of("words", List.of("密码")))),
                "短且含密码", null);
        assertEquals(0, v.ruleScore());
        assertTrue(v.shortCircuited());
        assertEquals(2, v.hits().size(), "短路前应保留已记录的封分命中");
    }

    @Test
    void invalidRuleConfigFailsClosedHumanReadable() {
        assertThrows(ValidationException.class, () -> engine.run(
                List.of(rule("truncated-tail", Map.of("action", "explode"))), "x", null));
        assertThrows(ValidationException.class, () -> engine.run(
                List.of(rule("truncated-tail", Map.of("action", "cap", "capScore", 150))), "x", null));
        assertThrows(ValidationException.class, () -> engine.run(
                List.of(rule("ghost-checker", Map.of())), "x", null));
    }

    @Test
    void expectedPassedToCheckersForExactCompare() {
        RuleVerdict v = engine.run(List.of(rule("length-range", Map.of("min", 5))),
                "ab", "一个很长的参考答案文本");
        assertEquals(0, v.ruleScore(), "短路命中=0 分");
    }

    private static boolean hasHit(RuleVerdict v) {
        return !v.hits().isEmpty();
    }
}
