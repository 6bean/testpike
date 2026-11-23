package com.sixbean.testpike.common.aichat;

import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Canonicalisation rules of the dialog user-image channel .
 * The single validation point mirrors the chip posture: a malformed entry
 * refuses the whole run by name, never a silent drop.
 */
class ChatImageAttachmentsTest {

    private static final String OK_KEY = "tp/7/chat-image/202610/a1b2c3d4-photo.png";

    @Test
    void nullAndEmptyInputsCanonicaliseToEmptyList() {
        assertEquals(List.of(), ChatImageAttachments.canonicalize(null, 4));
        assertEquals(List.of(), ChatImageAttachments.canonicalize(List.of(), 4));
    }

    @Test
    void blankObjectKeyIsRefusedNotDropped() {
        var refused = assertThrows(ServiceException.class, () -> ChatImageAttachments.canonicalize(
                List.of(new ChatImageAttachment("  ", "x.png")), 4));
        assertTrue(refused.getMessage().contains("图片附件缺少存储键"));
    }

    @Test
    void malformedObjectKeyRefusesByNamingTheOffendingValue() {
        var refused = assertThrows(ServiceException.class, () -> ChatImageAttachments.canonicalize(
                List.of(new ChatImageAttachment("no/such/shape", "x.png")), 4));
        assertTrue(refused.getMessage().contains("no/such/shape"));
    }

    @Test
    void objectKeyOfAnotherSceneIsRefused() {
        // exec-artifact keys ride the F-8 door, never the user-image channel.
        var refused = assertThrows(ServiceException.class, () -> ChatImageAttachments.canonicalize(
                List.of(new ChatImageAttachment("tp/7/exec-artifact/202610/a1b2c3d4-s.png", "x.png")), 4));
        assertTrue(refused.getMessage().contains("exec-artifact"));
    }

    @Test
    void duplicatesByObjectKeyCollapseKeepingFirstArrival() {
        List<ChatImageAttachment> out = ChatImageAttachments.canonicalize(List.of(
                new ChatImageAttachment(OK_KEY, "first.png"),
                new ChatImageAttachment(OK_KEY, "second.png")), 4);
        assertEquals(1, out.size());
        assertEquals("first.png", out.get(0).fileName());
    }

    @Test
    void exceedingTheCapRefusesNamingBothNumbers() {
        var refused = assertThrows(ServiceException.class, () -> ChatImageAttachments.canonicalize(
                List.of(
                        new ChatImageAttachment("tp/7/chat-image/202610/00000000-a.png", "a"),
                        new ChatImageAttachment("tp/7/chat-image/202610/11111111-b.png", "b"),
                        new ChatImageAttachment("tp/7/chat-image/202610/22222222-c.png", "c"),
                        new ChatImageAttachment("tp/7/chat-image/202610/33333333-d.png", "d"),
                        new ChatImageAttachment("tp/7/chat-image/202610/44444444-e.png", "e")), 4));
        assertTrue(refused.getMessage().contains("5"));
        assertTrue(refused.getMessage().contains("4"));
    }

    @Test
    void longFileNameIsCutNotRefused() {
        String name = "n".repeat(300);
        List<ChatImageAttachment> out = ChatImageAttachments.canonicalize(
                List.of(new ChatImageAttachment(OK_KEY, name)), 4);
        assertEquals(ChatImageAttachments.FILE_NAME_MAX_CHARS, out.get(0).fileName().length());
    }

    @Test
    void nullFileNameNormalisesToEmptyLabel() {
        List<ChatImageAttachment> out = ChatImageAttachments.canonicalize(
                List.of(new ChatImageAttachment(OK_KEY, null)), 4);
        assertEquals("", out.get(0).fileName());
    }

    @Test
    void projectIdOfReadsTheSliceSegmentOfAValidKey() {
        assertEquals(7L, ChatImageAttachments.projectIdOf(OK_KEY));
    }

    @Test
    void capKeyAndShippedDefaultAreRegistered() {
        assertEquals("ai.chat.image.max-attachments", ChatImageAttachments.KEY_MAX_IMAGES);
        assertEquals(4, ChatImageAttachments.DEFAULT_MAX_IMAGES);
    }
}
