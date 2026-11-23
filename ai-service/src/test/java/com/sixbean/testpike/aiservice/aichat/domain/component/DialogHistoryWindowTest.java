package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ChatMessageRemote;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-flight prompt echo removal : the core
 * listMessages door seals the user row of the CURRENT turn as soon as the run
 * opens, so the raw history handed to the assembler still ends with the very
 * prompt the assembler re-appends as the live user message. This window drops
 * exactly that trailing echo — once — and never touches any other row, so the
 * provider request carries the current question a single time.
 */
class DialogHistoryWindowTest {

    private static ChatMessageRemote row(long id, String role, String content) {
        return new ChatMessageRemote(id, 7L, role, content, null, "done",
                null, null, 0, 0, LocalDateTime.now(), null, null);
    }

    @Test
    void dropsTrailingUserEchoOfTheCurrentPrompt() {
        List<ChatMessageRemote> history = List.of(
                row(1, "user", "第一个问题"),
                row(2, "assistant", "第一个回答"),
                row(3, "user", "第二个问题"));

        List<ChatMessageRemote> window =
                DialogHistoryWindow.withoutInFlightPrompt(history, "第二个问题");

        assertEquals(2, window.size());
        assertSame(history.get(0), window.get(0));
        assertSame(history.get(1), window.get(1));
    }

    @Test
    void dropsTheEchoOnlyOnceWhenTheSameQuestionWasAskedBefore() {
        List<ChatMessageRemote> history = List.of(
                row(1, "user", "重复问题"),
                row(2, "assistant", "回答一"),
                row(3, "user", "重复问题"));

        List<ChatMessageRemote> window =
                DialogHistoryWindow.withoutInFlightPrompt(history, "重复问题");

        assertEquals(2, window.size());
        assertEquals("user", window.get(0).role());
    }

    @Test
    void keepsEveryOtherShapeUntouched() {
        List<ChatMessageRemote> endsWithAssistant = List.of(
                row(1, "user", "问题"),
                row(2, "assistant", "回答"));
        assertEquals(2, DialogHistoryWindow
                .withoutInFlightPrompt(endsWithAssistant, "新问题").size());

        List<ChatMessageRemote> differentTrailingPrompt = List.of(
                row(1, "assistant", "回答"),
                row(2, "user", "注入的补充"));
        assertEquals(2, DialogHistoryWindow
                .withoutInFlightPrompt(differentTrailingPrompt, "本轮提问").size());
    }

    @Test
    void blankCurrentPromptDropsNothingEvenOnATrailingUserRow() {
        List<ChatMessageRemote> history = List.of(row(1, "user", "上一轮问题"));

        assertEquals(1, DialogHistoryWindow.withoutInFlightPrompt(history, null).size());
        assertEquals(1, DialogHistoryWindow.withoutInFlightPrompt(history, "  ").size());
    }

    @Test
    void emptyHistoryPassesThroughAsEmpty() {
        assertTrue(DialogHistoryWindow.withoutInFlightPrompt(List.of(), "问题").isEmpty());
    }
}
