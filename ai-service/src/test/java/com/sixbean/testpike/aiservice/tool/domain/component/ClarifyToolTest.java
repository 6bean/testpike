package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shape tests of the built-in chat.clarify FC tool :
 * the dialog's honest-suspension channel. The model may restate its question
 * and hand the user 2-4 one-line interpretations to pick from, but only when
 * a key slot is missing and the cost of guessing wrong is high (contract ⑥:
 * low-risk ambiguity must be answered under a stated assumption instead).
 * A clarify call EXECUTES nothing and steers the turn to end — the user's
 * pick arrives as a normal follow-up message, never a smuggled tool result.
 */
class ClarifyToolTest {

    private static final String VALID_BODY = """
            {"question":"要给哪个项目生成用例？",\
            "options":["回归演示项目：给现有需求点补一批用例",\
            "testPike 体验样例：按新上传的需求文档新建用例"],\
            "missingSlot":"目标项目"}""";

    @Test
    void builtInNameRoutesOnlyTheClarifyCall() {
        assertEquals("chat.clarify", ClarifyTool.NAME);
        assertTrue(ClarifyTool.isClarifyCall("chat.clarify"));
        assertFalse(ClarifyTool.isClarifyCall("plan.propose"));
        assertFalse(ClarifyTool.isClarifyCall("aggregate.query"));
        assertFalse(ClarifyTool.isClarifyCall(null));
    }

    @Test
    void readsRestatementOptionsAndMissingSlot() {
        assertEquals("要给哪个项目生成用例？", ClarifyTool.readQuestion(VALID_BODY));
        List<String> options = ClarifyTool.readOptions(VALID_BODY);
        assertEquals(2, options.size());
        assertTrue(options.get(0).startsWith("回归演示项目"));
        assertEquals("目标项目", ClarifyTool.readMissingSlot(VALID_BODY));
    }

    @Test
    void blankOrGarbageBodiesParseToTheEmptyShape() {
        for (String broken : new String[] {"", "  ", null}) {
            assertEquals("", ClarifyTool.readQuestion(broken));
            assertTrue(ClarifyTool.readOptions(broken).isEmpty());
        }
        assertEquals("", ClarifyTool.readQuestion("{not json"));
        assertTrue(ClarifyTool.readOptions("{not json").isEmpty());
        assertEquals("", ClarifyTool.readMissingSlot("{not json"));
        // Well-formed JSON without the slots is the same honest emptiness —
        // a missing slot must never grow a hallucinated label.
        assertTrue(ClarifyTool.readQuestion("{}").isEmpty());
        assertTrue(ClarifyTool.readOptions("{}").isEmpty());
    }

    @Test
    void blankOptionEntriesAreFilteredOut() {
        String padded = """
                {"question":"选哪种方案？",\
                "options":["方案A：按需求点全覆盖","","  ","方案B：只补高风险项"],\
                "missingSlot":"覆盖范围"}""";
        assertEquals(2, ClarifyTool.readOptions(padded).size());
    }

    @Test
    void questionWithoutTwoToFourOptionsIsRefusedNotCarded() {
        // One option is not a question — it is a statement the user cannot
        // "clarify"; five options break the ≤2-click contract by turning the
        // card into a list to read.
        String oneOption = "{\"question\":\"哪个项目？\",\"options\":[\"回归演示项目\"]}";
        StringBuilder five = new StringBuilder("{\"question\":\"哪个项目？\",\"options\":[");
        for (int i = 1; i <= 5; i++) {
            five.append(i > 1 ? ",\"解释" + i + "\"" : "\"解释1\"");
        }
        five.append("]}");
        String emptyOptions = "{\"question\":\"哪个项目？\",\"options\":[]}";
        for (String outOfShape : List.of(oneOption, five.toString(), emptyOptions)) {
            ToolCallOutcome refused = ClarifyTool.clarify(outOfShape);
            assertFalse(refused.success(), "不合形态的追问必须被拒: " + outOfShape);
        }
        assertTrue(ClarifyTool.clarify(VALID_BODY).success());
    }

    @Test
    void acceptedClarifySteersTheTurnToEndWithoutExecuting() {
        ToolCallOutcome outcome = ClarifyTool.clarify(VALID_BODY);
        assertTrue(outcome.success());
        assertEquals(ClarifyTool.NAME, outcome.toolName());
        // The guidance is model-facing: stop for this turn, no other tool
        // calls, the user's answer arrives as the next message.
        assertTrue(outcome.content().contains("本轮"), outcome.content());
        assertTrue(outcome.content().contains("不要再调用"), outcome.content());
    }

    @Test
    void perTurnBudgetReadFallsBackToFactoryDefault() {
        CoreGateway gateway = mock(CoreGateway.class);
        assertEquals("ai.chat.clarify-per-turn-max", ClarifyTool.KEY_PER_TURN_MAX);
        assertEquals(1, ClarifyTool.DEFAULT_PER_TURN_MAX);

        when(gateway.getSysConfig(ClarifyTool.KEY_PER_TURN_MAX)).thenReturn(null);
        assertEquals(1, ClarifyTool.readMaxPerTurn(gateway));
        when(gateway.getSysConfig(ClarifyTool.KEY_PER_TURN_MAX)).thenReturn("  ");
        assertEquals(1, ClarifyTool.readMaxPerTurn(gateway));
        when(gateway.getSysConfig(ClarifyTool.KEY_PER_TURN_MAX)).thenReturn("garbage");
        assertEquals(1, ClarifyTool.readMaxPerTurn(gateway));
        when(gateway.getSysConfig(ClarifyTool.KEY_PER_TURN_MAX)).thenReturn(" 2 ");
        assertEquals(2, ClarifyTool.readMaxPerTurn(gateway));
    }

    @Test
    void toolDefinitionCarriesTheQuestionOptionsSlotsSchema() {
        ChatToolDef def = ClarifyTool.toolDef();
        assertEquals(ClarifyTool.NAME, def.function().name());
        String schema = String.valueOf(def.function().parameters());
        assertTrue(schema.contains("question"), schema);
        assertTrue(schema.contains("options"), schema);
        assertTrue(schema.contains("missingSlot"), schema);
        // The description itself is the clause lock: 2-4 one-line options,
        // high-cost ambiguity only, turn ends right after the call.
        assertTrue(def.function().description().contains("2-4"), def.function().description());
        assertTrue(def.function().description().contains("本轮"), def.function().description());
    }
}
