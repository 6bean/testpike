package com.sixbean.testpike.aiservice.gen.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.api.dto.ScenarioItem;
import com.sixbean.testpike.aiservice.gen.api.dto.ScenarioKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage0 scenario enumeration contract : the model output is parsed
 * into a strict scenario list (every scenario must carry a source-text anchor,
 * unknown kinds are refused), the priority strategy keeps P0 rich (counter
 * examples and boundaries mandatory) while P2 stays on the main flow only, and
 * the per-requirement cap reads the hot K8 row with a 1..20 clamp.
 * Pure in-process judgement — no mocks, no I/O.
 */
class ScenarioEnumerationTest {

    private final ScenarioListParser parser = new ScenarioListParser(new ObjectMapper());

    private static final String FULL_LIST = """
            {"scenarios":[
              {"kind":"main-flow","title":"正常提交表单","anchor":"用户填写必填项后点击提交"},
              {"kind":"equivalence","title":"同值多别名归组","anchor":"别名支持中英文逗号分隔"},
              {"kind":"counter-example","title":"必填项为空被拒","anchor":"必填项未填写时禁止提交"},
              {"kind":"boundary","title":"别名数量上限","anchor":"最多 50 个别名"},
              {"kind":"state-combination","title":"草稿态编辑后停用","anchor":"停用状态下不可编辑"}
            ]}""";

    @Test
    void parsesFullScenarioListAndNormalizesKinds() {
        List<ScenarioItem> items = parser.parse(FULL_LIST);
        assertEquals(5, items.size());
        assertEquals(ScenarioKind.MAIN_FLOW, items.get(0).kind());
        assertEquals(ScenarioKind.STATE_COMBINATION, items.get(4).kind());
        assertEquals("最多 50 个别名", items.get(3).anchor());
    }

    @Test
    void toleratesFencedOutputWithSurroundingProse() {
        String messy = "以下是场景清单：\n```json\n" + FULL_LIST + "\n```\n完毕。";
        assertEquals(5, parser.parse(messy).size());
    }

    @Test
    void refusesUnknownScenarioKind() {
        String bad = "{\"scenarios\":[{\"kind\":\"telepathy\",\"title\":\"x\",\"anchor\":\"y\"}]}";
        IllegalArgumentException rejected =
                assertThrows(IllegalArgumentException.class, () -> parser.parse(bad));
        assertTrue(rejected.getMessage().contains("telepathy"), rejected.getMessage());
    }

    @Test
    void requiresSourceAnchorOnEveryScenario() {
        String noAnchor = "{\"scenarios\":[{\"kind\":\"boundary\",\"title\":\"上限\"}]}";
        assertThrows(IllegalArgumentException.class, () -> parser.parse(noAnchor));
    }

    @Test
    void refusesMissingOrEmptyScenarioArray() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{\"steps\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{\"scenarios\":[]}"));
    }

    @Test
    void dropsDuplicatedKindTitlePairs() {
        String dup = "{\"scenarios\":["
                + "{\"kind\":\"boundary\",\"title\":\"上限\",\"anchor\":\"a1\"},"
                + "{\"kind\":\"boundary\",\"title\":\"上限\",\"anchor\":\"a2\"}]}";
        assertEquals(1, parser.parse(dup).size());
    }

    @Test
    void p2StrategyKeepsOnlyMainFlowAndEquivalence() {
        List<ScenarioItem> kept = ScenarioStrategy.retainedForPriority(parser.parse(FULL_LIST), 2);
        assertEquals(2, kept.size());
        assertTrue(kept.stream().allMatch(i -> i.kind() == ScenarioKind.MAIN_FLOW
                || i.kind() == ScenarioKind.EQUIVALENCE));
    }

    @Test
    void p0AndP1StrategiesKeepTheWholeScenarioList() {
        assertEquals(5, ScenarioStrategy.retainedForPriority(parser.parse(FULL_LIST), 0).size());
        assertEquals(5, ScenarioStrategy.retainedForPriority(parser.parse(FULL_LIST), 1).size());
    }

    @Test
    void p0MandatesCounterExampleAndBoundary() {
        List<ScenarioItem> thin = List.of(
                new ScenarioItem(ScenarioKind.MAIN_FLOW, "t", "a", null));
        List<ScenarioKind> missing = ScenarioStrategy.missingMandatoryKinds(0, thin);
        assertEquals(List.of(ScenarioKind.COUNTER_EXAMPLE, ScenarioKind.BOUNDARY), missing);
        assertTrue(ScenarioStrategy.missingMandatoryKinds(0, parser.parse(FULL_LIST)).isEmpty());
    }

    @Test
    void p2MandatesMainFlowOnly() {
        List<ScenarioItem> noMain = List.of(
                new ScenarioItem(ScenarioKind.EQUIVALENCE, "t", "a", null));
        assertEquals(List.of(ScenarioKind.MAIN_FLOW), ScenarioStrategy.missingMandatoryKinds(2, noMain));
        assertTrue(ScenarioStrategy.missingMandatoryKinds(1, noMain).isEmpty());
    }

    @Test
    void capReadsRawConfigWithFactoryEightAndOneToTwentyClamp() {
        assertEquals(8, ScenarioStrategy.scenarioCap(null));
        assertEquals(8, ScenarioStrategy.scenarioCap("  "));
        assertEquals(8, ScenarioStrategy.scenarioCap("not-a-number"));
        assertEquals(1, ScenarioStrategy.scenarioCap("0"));
        assertEquals(20, ScenarioStrategy.scenarioCap("99"));
        assertEquals(12, ScenarioStrategy.scenarioCap(" 12 "));
    }

    @Test
    void capTruncatesOverlongScenarioList() {
        List<ScenarioItem> items = parser.parse(FULL_LIST);
        assertEquals(2, ScenarioStrategy.cap(items, 2).size());
        assertEquals(items, ScenarioStrategy.cap(items, 8));
    }

    @Test
    void governanceKeyLiteralsMatchTheAdjudicatedRows() {
        assertEquals("ai.gen.scenario-expand.enabled", ScenarioStrategy.KEY_EXPAND_ENABLED);
        assertEquals("ai.gen.scenario-max-per-requirement", ScenarioStrategy.KEY_MAX_PER_REQUIREMENT);
    }
}
