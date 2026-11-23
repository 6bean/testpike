package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape and delivery-gate tests of the built-in chat.suggest FC tool
 * . A suggestion proposes
 * 1-3 next-step questions, each naming the capability it would use; the
 * server-side gate drops any proposal whose capability is outside the
 * turn's mounted intersection, so a chip that cannot be honored never
 * reaches the screen. Unlike chat.clarify a suggestion NEVER ends the turn
 * and EXECUTES nothing.
 */
class SuggestToolTest {

    private static final String VALID_BODY = """
            {"suggestions":[{"text":"把缺覆盖的需求点补一批用例","capability":"case.write"},\
            {"text":"列出最近失败的执行计划","capability":"aggregate.query"}]}""";

    @Test
    void builtInNameRoutesOnlyTheSuggestCall() {
        assertEquals("chat.suggest", SuggestTool.NAME);
        assertTrue(SuggestTool.isSuggestCall("chat.suggest"));
        assertFalse(SuggestTool.isSuggestCall("chat.clarify"));
        assertFalse(SuggestTool.isSuggestCall("plan.propose"));
        assertFalse(SuggestTool.isSuggestCall(null));
    }

    @Test
    void readsItemsWithTextAndCapability() {
        List<SuggestTool.Suggestion> items = SuggestTool.readSuggestions(VALID_BODY);
        assertEquals(2, items.size());
        assertEquals("把缺覆盖的需求点补一批用例", items.get(0).text());
        assertEquals("case.write", items.get(0).capability());
        assertEquals("aggregate.query", items.get(1).capability());
    }

    @Test
    void blankOrGarbageBodiesParseToTheEmptyShape() {
        for (String broken : new String[] {"", "  ", null}) {
            assertTrue(SuggestTool.readSuggestions(broken).isEmpty());
        }
        assertTrue(SuggestTool.readSuggestions("{not json").isEmpty());
        assertTrue(SuggestTool.readSuggestions("{}").isEmpty());
        assertTrue(SuggestTool.readSuggestions("{\"suggestions\":\"not-an-array\"}").isEmpty());
        assertFalse(SuggestTool.isAcceptedShape("{not json"));
    }

    @Test
    void blankTextEntriesAreFilteredAndCountsBoundAcceptance() {
        // Blank-text entries never count: a chip with no sentence is noise.
        String padded = """
                {"suggestions":[{"text":"","capability":"case.write"},\
                {"text":"  ","capability":"case.write"},\
                {"text":"生成边界用例","capability":"case.write"}]}""";
        assertEquals(1, SuggestTool.readSuggestions(padded).size());
        assertTrue(SuggestTool.isAcceptedShape(padded));
        assertFalse(SuggestTool.isAcceptedShape("{\"suggestions\":[]}"));
        String four = """
                {"suggestions":[{"text":"一","capability":"a"},{"text":"二","capability":"b"},\
                {"text":"三","capability":"c"},{"text":"四","capability":"d"}]}""";
        assertFalse(SuggestTool.isAcceptedShape(four));
        // A blank capability still reads as an item (the gate drops it later,
        // the shape predicate never invents a routing name on its own).
        assertTrue(SuggestTool.isAcceptedShape(
                "{\"suggestions\":[{\"text\":\"生成边界用例\",\"capability\":\"\"}]}"));
    }

    @Test
    void acceptedGateKeepsOnlyIntersectionRidesAndDropsBlankCapability() {
        List<SuggestTool.Suggestion> items = SuggestTool.readSuggestions(VALID_BODY);
        List<SuggestTool.Suggestion> kept =
                SuggestTool.filterAllowed(items, Set.of("case.write", "aggregate.query"));
        assertEquals(2, kept.size());
        // Out-of-scope capability (not mounted this turn) = whole entry dropped.
        assertEquals(List.of(), SuggestTool.filterAllowed(items, Set.of("defect.write")));
        assertEquals(1, SuggestTool.filterAllowed(items, Set.of("case.write")).size());
        // Blank capability is unhonorable = dropped; empty allow-set drops all.
        assertEquals(List.of(), SuggestTool.filterAllowed(
                List.of(new SuggestTool.Suggestion("问题", " ")), Set.of("case.write")));
        assertEquals(List.of(), SuggestTool.filterAllowed(items, Set.of()));
        assertEquals(List.of(), SuggestTool.filterAllowed(items, null));
    }

    @Test
    void validShapeAcknowledgesWithoutEndingTheTurnAndBrokenShapeIsRefused() {
        ToolCallOutcome ok = SuggestTool.suggest(VALID_BODY);
        assertTrue(ok.success());
        assertTrue(ok.content().contains("2 条"));
        assertFalse(ok.content().contains("本轮到此结束"));
        ToolCallOutcome refused = SuggestTool.suggest("{\"suggestions\":[]}");
        assertFalse(refused.success());
        assertTrue(refused.content().contains("1-3"));
    }

    @Test
    void toolDefCarriesTheBuiltInNameAndParsableSchema() {
        var def = SuggestTool.toolDef();
        assertEquals("chat.suggest", def.function().name());
        assertTrue(def.function().description().contains("1-3"));
    }

    @Test
    void transcriptReadingTakesTheFirstAcceptedCallOnly() {
        // Mirrors the loop's transcript: assistant tool_calls + tool result.
        // A refused earlier call stays out, the accepted one wins; later
        // duplicates never double the chip set (one card per turn posture).
        List<ChatMessage> messages = List.of(
                ChatMessage.assistantWithToolCalls("", List.of(
                        new LlmToolCall("c1", "chat.suggest", "{\"suggestions\":[]}"))),
                ChatMessage.assistantWithToolCalls("", List.of(
                        new LlmToolCall("c2", "chat.suggest", VALID_BODY),
                        new LlmToolCall("c3", "chat.suggest", """
                                {"suggestions":[{"text":"再补一批性能用例","capability":"perf.launch"}]}"""))),
                ChatMessage.tool("c2", "扩展建议已记录 2 条"));
        ReActOutcome outcome = new ReActOutcome(null, messages, 2, 1, 1, false, null);
        List<SuggestTool.Suggestion> read = SuggestTool.readAcceptedSuggestions(outcome);
        assertEquals(2, read.size());
        assertEquals("把缺覆盖的需求点补一批用例", read.get(0).text());
        assertEquals("aggregate.query", read.get(1).capability());
        // No suggest call at all = empty, never a hallucinated chip.
        assertTrue(SuggestTool.readAcceptedSuggestions(
                new ReActOutcome(null, List.of(new ChatMessage("user", "问题")),
                        0, 0, 0, false, null)).isEmpty());
    }
}
