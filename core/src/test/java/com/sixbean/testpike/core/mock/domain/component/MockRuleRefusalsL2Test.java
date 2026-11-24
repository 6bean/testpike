package com.sixbean.testpike.core.mock.domain.component;

import static com.sixbean.testpike.core.support.RefusalGuidanceAssertions.assertThreeElements;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the R63 three-element contract for every refusal raised by the mock
 * save gate (space form + rule form) without booting infrastructure: the
 * offending value is echoed, the repair entry is quoted with its on-screen
 * label, an action word tells the user what to do next, and no sentence ends
 * in a dead end.
 *
 * <p>The quoted literals inside each refusal must match the shipped frontend
 * wording: the simulation-space page with its create-space dialog, the
 * simulation-docs page with its structured rule form and row edit entry, and
 * the state-scenario page with its create dialog. Keep them in sync when the
 * screens change.
 */
class MockRuleRefusalsL2Test {

    @Test
    void spaceKeyIllegalEchoesValueAndQuotesTheField() {
        assertThreeElements(MockRuleRefusals.spaceKeyIllegal("Order Api"), "Order Api");
        assertTrue(MockRuleRefusals.spaceKeyIllegal("Order Api").contains("新建空间"));
    }

    @Test
    void spaceAliasIllegalEchoesValueAndQuotesTheField() {
        assertThreeElements(MockRuleRefusals.spaceAliasIllegal("Bad Alias"), "Bad Alias");
    }

    @Test
    void rateLimitNotPositiveEchoesTheNumber() {
        assertThreeElements(MockRuleRefusals.rateLimitNotPositive(0), "0");
        assertTrue(MockRuleRefusals.rateLimitNotPositive(-3).contains("-3"));
    }

    @Test
    void dailyQuotaNotPositiveEchoesTheNumber() {
        assertThreeElements(MockRuleRefusals.dailyQuotaNotPositive(0L), "0");
    }

    @Test
    void chaosOutOfRangeEchoesTheValueAndNamesTheRange() {
        assertThreeElements(MockRuleRefusals.chaosOutOfRange(130), "130", "0-100");
    }

    @Test
    void pathPatternMissingSlashEchoesPatternAndPointsToTheRuleForm() {
        assertThreeElements(MockRuleRefusals.pathPatternMissingSlash("orders/list"), "orders/list");
        assertTrue(MockRuleRefusals.pathPatternMissingSlash("orders/list").contains("结构化新建规则"));
    }

    @Test
    void regexPathUncompilableEchoesPatternAndCompilerReason() {
        assertThreeElements(
                MockRuleRefusals.regexPathUncompilable("/orders/(?<id>", "Unclosed group"),
                "/orders/(?<id>", "Unclosed group");
    }

    @Test
    void templateBracesUnpairedEchoesPatternAndGivesTheLegalShape() {
        assertThreeElements(MockRuleRefusals.templateBracesUnpaired("/orders/{id"), "/orders/{id");
    }

    @Test
    void scenarioNameTooLongEchoesBothLengths() {
        assertThreeElements(MockRuleRefusals.scenarioNameTooLong(92), "92", "80");
    }

    @Test
    void matchersMustBeArrayQuotesTheCardLabel() {
        assertThreeElements(MockRuleRefusals.matchersMustBeArray("{\"op\":\"equals\"}"),
                "{\"op\":\"equals\"}");
    }

    @Test
    void responseMustBeObjectQuotesTheCardLabel() {
        assertThreeElements(MockRuleRefusals.responseMustBeObject("[200]"), "[200]");
    }

    @Test
    void jsonMalformedEchoesPayloadAndParserReason() {
        assertThreeElements(
                MockRuleRefusals.jsonMalformed("「匹配条件」", "[{\"op\":", "Unexpected end-of-input"),
                "[{\"op\":", "Unexpected end-of-input");
    }

    @Test
    void blankRequiredQuotesTheFieldAndNamesTheEntry() {
        assertThreeElements(MockRuleRefusals.blankRequired("「空间名称」", "「仿真空间」页的「新建空间」"),
                "空间名称");
    }

    @Test
    void tooLongEchoesActualAndLimit() {
        assertThreeElements(MockRuleRefusals.tooLong("「规则名称」", 120, 141), "141", "120");
    }

    @Test
    void valueNotAllowedEchoesValueAndTheAllowedSpectrum() {
        String sentence = MockRuleRefusals.valueNotAllowed(
                "「请求方法」", "TRACE", List.of("ANY", "GET", "POST"), "「编辑」表单");
        assertThreeElements(sentence, "TRACE", "ANY", "POST");
    }

    @Test
    void noRefusalSentenceEverEndsInADeadEnd() {
        List<String> all = List.of(
                MockRuleRefusals.spaceKeyIllegal("x"),
                MockRuleRefusals.spaceAliasIllegal("x"),
                MockRuleRefusals.rateLimitNotPositive(0),
                MockRuleRefusals.dailyQuotaNotPositive(0L),
                MockRuleRefusals.chaosOutOfRange(130),
                MockRuleRefusals.pathPatternMissingSlash("x"),
                MockRuleRefusals.regexPathUncompilable("x", "boom"),
                MockRuleRefusals.templateBracesUnpaired("x"),
                MockRuleRefusals.scenarioNameTooLong(92),
                MockRuleRefusals.matchersMustBeArray("{}"),
                MockRuleRefusals.responseMustBeObject("[]"),
                MockRuleRefusals.jsonMalformed("「响应行为」", "{", "boom"),
                MockRuleRefusals.blankRequired("「场景名称」", "「新建场景」"),
                MockRuleRefusals.tooLong("「路径」", 300, 301),
                MockRuleRefusals.valueNotAllowed("「鉴权模式」", "jwt", List.of("none"), "「新建空间」"));
        for (String sentence : all) {
            assertTrue(sentence.contains("」") && sentence.contains("请"),
                    "every refusal must quote an entry and tell the next step: [" + sentence + "]");
        }
    }
}
