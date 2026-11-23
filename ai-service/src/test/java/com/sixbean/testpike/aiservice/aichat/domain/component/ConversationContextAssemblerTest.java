package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ChatMessageRemote;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AP-1 the rolling-summary assembly order for the dialog
 * request — frozen system head + pinned facts section (independent of the
 * lossy summary layer) + raw text of the last N turns + summary of older
 * turns folded before the raw window. Pure function: no gateway, no LLM.
 */
class ConversationContextAssemblerTest {

    private static final String HEAD = "你是测试系统助手。";

    private static ChatMessageRemote msg(long id, String role, String content) {
        return new ChatMessageRemote(id, 1L, role, content, null, "done",
                null, null, 0, 0, LocalDateTime.of(2026, 11, 1, 10, 0), null, null);
    }

    /** Ten closed turns: user_i + assistant_i, chronological. */
    private static List<ChatMessageRemote> turns(int count) {
        List<ChatMessageRemote> history = new ArrayList<>();
        long id = 1;
        for (int i = 1; i <= count; i++) {
            history.add(msg(id++, "user", "问题" + i));
            history.add(msg(id++, "assistant", "回答" + i));
        }
        return history;
    }

    private static ConversationAssemblyRequest request(List<ChatMessageRemote> history,
                                                       List<String> pinnedFacts,
                                                       int keepRecentTurns) {
        return new ConversationAssemblyRequest(HEAD, pinnedFacts, history, "当前提问", keepRecentTurns);
    }

    @Test
    void emptyHistoryKeepsFrozenHeadPinsAndCurrentPrompt() {
        List<ChatMessage> out = ConversationContextAssembler.assemble(
                request(List.of(), List.of("用户已确认：目标环境=预发"), 6),
                earlier -> "SHOULD_NOT_BE_CALLED");
        assertEquals(2, out.size());
        assertEquals("system", out.get(0).role());
        assertTrue(out.get(0).content().startsWith(HEAD), "frozen head lands first");
        assertTrue(out.get(0).content().contains("用户已确认：目标环境=预发"), "pinned fact visible");
        assertFalse(out.get(0).content().contains("更早对话摘要"), "no summary section without older turns");
        assertEquals("user", out.get(1).role());
        assertEquals("当前提问", out.get(1).content());
    }

    @Test
    void keepRecentWindowKeepsRawTurnsAndFoldsOlderIntoSummary() {
        List<ChatMessageRemote> olderSeen = new ArrayList<>();
        List<ChatMessage> out = ConversationContextAssembler.assemble(
                request(turns(10), List.of(), 6),
                earlier -> {
                    olderSeen.addAll(earlier);
                    return "前四轮讨论了用例分配";
                });
        assertEquals(8, olderSeen.size(), "4 oldest turns (8 messages) hand over to the summary layer");
        assertEquals("问题1", olderSeen.get(0).content());
        assertEquals("回答4", olderSeen.get(7).content());
        // system (head + summary) + 6 raw turns (12 messages) + current prompt
        assertEquals(1 + 12 + 1, out.size());
        assertTrue(out.get(0).content().contains("更早对话摘要"), "summary section lands in system");
        assertTrue(out.get(0).content().contains("前四轮讨论了用例分配"));
        assertEquals("user", out.get(1).role());
        assertEquals("问题5", out.get(1).content(), "raw window starts at turn 5");
        assertEquals("回答10", out.get(12).content(), "raw window keeps the newest turn");
        assertEquals("当前提问", out.get(13).content());
    }

    @Test
    void allTurnsWithinWindowNeverCallsSummarySource() {
        List<ChatMessage> out = ConversationContextAssembler.assemble(
                request(turns(3), List.of(), 6),
                earlier -> {
                    throw new AssertionError("summary source must not run when nothing is older");
                });
        assertEquals(1 + 6 + 1, out.size());
        assertFalse(out.get(0).content().contains("更早对话摘要"));
    }

    @Test
    void blankSummaryTextLandsNoSection() {
        List<ChatMessage> out = ConversationContextAssembler.assemble(
                request(turns(10), List.of(), 6), earlier -> "  ");
        assertTrue(out.get(0).content().startsWith(HEAD));
        assertFalse(out.get(0).content().contains("更早对话摘要"), "blank summary = no fabricated section");
        assertEquals(1 + 12 + 1, out.size());
    }

    @Test
    void pinnedFactsSurviveLossySummaryLayer() {
        List<ChatMessage> out = ConversationContextAssembler.assemble(
                request(turns(10), List.of("pin1", "pin2"), 6), earlier -> "摘要内容");
        String system = out.get(0).content();
        int pinIdx = system.indexOf("pin1");
        int summaryIdx = system.indexOf("摘要内容");
        assertTrue(pinIdx >= 0 && summaryIdx >= 0);
        assertTrue(pinIdx < summaryIdx, "pinned section lands before the lossy summary section");
    }

    @Test
    void nonPositiveKeepWindowFloorsToOneTurn() {
        List<ChatMessage> out = ConversationContextAssembler.assemble(
                request(turns(5), List.of(), 0), earlier -> "更早摘要");
        // 1 raw turn kept (2 messages) + system + current prompt
        assertEquals(1 + 2 + 1, out.size());
        assertEquals("问题5", out.get(1).content(), "newest turn always stays raw");
    }

    @Test
    void assistantWithoutContentIsSkippedFromRawWindow() {
        List<ChatMessageRemote> history = new ArrayList<>(turns(2));
        history.add(msg(99, "assistant", null));
        List<ChatMessage> out = ConversationContextAssembler.assemble(
                request(history, List.of(), 6), earlier -> "X");
        for (ChatMessage m : out) {
            assertFalse("assistant".equals(m.role()) && (m.content() == null || m.content().isBlank()),
                    "null-content assistant never reaches the provider");
        }
    }
}
