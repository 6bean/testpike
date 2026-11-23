package com.sixbean.testpike.core.aichat.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.aichat.ChatImageAttachment;
import com.sixbean.testpike.common.aichat.ChatImageAttachments;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiChatRunMessage;
import com.sixbean.testpike.core.aichat.api.dto.ChatFrame;
import com.sixbean.testpike.core.aichat.api.dto.ChatMessageInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionPageView;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import com.sixbean.testpike.core.aichat.domain.service.impl.ChatRunServiceImpl;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.executor.domain.component.InMemoryConfigServiceStub;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 spectra of the user-image attachment channel inside the run door
 * : core is the single canonicalisation point —
 * canonical keys ride the run message, malformed or foreign-project keys are
 * refused BEFORE any ledger row lands, the hot-readable cap refuses by naming
 * both numbers, and a request without the new field keeps the old shape
 * (zero drift: old clients send no attachments, the run opens as before).
 * <p>Infrastructure isolation only (L2 rule): the three collaborators are
 * in-memory fakes; the real admin→core→MQ→ai-service chain is proven by L3/L4.
 */
class ChatRunAttachmentsTest {

    private static final long SESSION_ID = 9L;
    private static final long USER_ID = 7L;
    private static final long PROJECT_ID = 7L;





    private static ChatSessionInfo session() {
        return new ChatSessionInfo(SESSION_ID, PROJECT_ID, USER_ID, "t", 0, null, 1, 0L, 0L);
    }

    private static ChatRunServiceImpl service(ChatRunDoorFakes.CapturingPublisher publisher,
                                              ChatRunDoorFakes.LedgerFrames frames,
                                              ConfigService config) {
        return new ChatRunServiceImpl(new ChatRunDoorFakes.SessionDoor(session()), frames,
                publisher, config, new ObjectMapper());
    }

    @Test
    void canonicalAttachmentsRideTheRunMessage() {
        ChatRunDoorFakes.CapturingPublisher publisher = new ChatRunDoorFakes.CapturingPublisher();
        ChatRunDoorFakes.LedgerFrames frames = new ChatRunDoorFakes.LedgerFrames();
        ChatRunServiceImpl service = service(publisher, frames, new InMemoryConfigServiceStub(Map.of()));
        List<ChatImageAttachment> raw = List.of(
                new ChatImageAttachment("tp/7/chat-image/202610/aaaa1111-shot.png", "  shot.png  "),
                new ChatImageAttachment("tp/7/chat-image/202610/aaaa1111-shot.png", "dup"),
                new ChatImageAttachment("tp/7/chat-image/202610/bbbb2222-bug.jpg", null));

        service.openRun(SESSION_ID, USER_ID, "看看这两张图", List.of(), raw);

        AiChatRunMessage message = publisher.published.get(0);
        assertEquals(2, message.attachments().size(), "duplicate key collapses, first arrival kept");
        assertEquals("tp/7/chat-image/202610/aaaa1111-shot.png",
                message.attachments().get(0).objectKey());
        assertEquals("shot.png", message.attachments().get(0).fileName());
        assertEquals("", message.attachments().get(1).fileName(), "missing label normalises to empty");
        // the canonical image list now also books the user-turn
        // ledger frame — open, frame, seal, then the assistant placeholder.
        assertEquals(List.of("open:user",
                "frame:state:101:本问附带图片 2 枚"
                        + "|{\"chatImages\":[{\"objectKey\":\"tp/7/chat-image/202610/aaaa1111-shot.png\","
                        + "\"fileName\":\"shot.png\"},"
                        + "{\"objectKey\":\"tp/7/chat-image/202610/bbbb2222-bug.jpg\","
                        + "\"fileName\":\"\"}]}",
                "close:done", "open:assistant"), frames.calls);
    }

    @Test
    void absentAttachmentsKeepTheOldRunShape() {
        ChatRunDoorFakes.CapturingPublisher publisher = new ChatRunDoorFakes.CapturingPublisher();
        ChatRunDoorFakes.LedgerFrames frames = new ChatRunDoorFakes.LedgerFrames();
        ChatRunServiceImpl service = service(publisher, frames, new InMemoryConfigServiceStub(Map.of()));

        service.openRun(SESSION_ID, USER_ID, "老形状问题", List.of(), null);

        assertEquals(List.of(), publisher.published.get(0).attachments(),
                "null wire list canonicalises to an empty canonical list");
        assertEquals(3, frames.calls.size(), "the run opens exactly the two rows as before");
    }

    @Test
    void malformedKeyRefusesBeforeAnyRowLands() {
        ChatRunDoorFakes.CapturingPublisher publisher = new ChatRunDoorFakes.CapturingPublisher();
        ChatRunDoorFakes.LedgerFrames frames = new ChatRunDoorFakes.LedgerFrames();
        ChatRunServiceImpl service = service(publisher, frames, new InMemoryConfigServiceStub(Map.of()));

        ServiceException refused = assertThrows(ServiceException.class, () -> service.openRun(
                SESSION_ID, USER_ID, "坏键", List.of(),
                List.of(new ChatImageAttachment("tp/7/exec-artifact/202610/x.png", "x"))));

        assertTrue(refused.getMessage().contains("tp/7/exec-artifact/202610/x.png"),
                "the refusal names the offending key");
        assertTrue(frames.calls.isEmpty(), "a refused run leaves no half-written turn behind");
        assertTrue(publisher.published.isEmpty());
    }

    @Test
    void foreignProjectKeyRefusesHorizontally() {
        ChatRunDoorFakes.CapturingPublisher publisher = new ChatRunDoorFakes.CapturingPublisher();
        ChatRunDoorFakes.LedgerFrames frames = new ChatRunDoorFakes.LedgerFrames();
        ChatRunServiceImpl service = service(publisher, frames, new InMemoryConfigServiceStub(Map.of()));

        ServiceException refused = assertThrows(ServiceException.class, () -> service.openRun(
                SESSION_ID, USER_ID, "别人的图", List.of(),
                List.of(new ChatImageAttachment("tp/88/chat-image/202610/cccc3333-p.png", "p"))));

        assertTrue(refused.getMessage().contains("tp/88/chat-image/202610/cccc3333-p.png"));
        assertTrue(frames.calls.isEmpty(), "H1 refusal lands no row");
    }

    @Test
    void capOverrunNamesBothNumbers() {
        ChatRunDoorFakes.CapturingPublisher publisher = new ChatRunDoorFakes.CapturingPublisher();
        ChatRunDoorFakes.LedgerFrames frames = new ChatRunDoorFakes.LedgerFrames();
        ConfigService config = new InMemoryConfigServiceStub(Map.of(ChatImageAttachments.KEY_MAX_IMAGES, "2"));
        ChatRunServiceImpl service = service(publisher, frames, config);

        ServiceException refused = assertThrows(ServiceException.class, () -> service.openRun(
                SESSION_ID, USER_ID, "三张图", List.of(), List.of(
                        new ChatImageAttachment("tp/7/chat-image/202610/11111111-a.png", "a"),
                        new ChatImageAttachment("tp/7/chat-image/202610/22222222-b.png", "b"),
                        new ChatImageAttachment("tp/7/chat-image/202610/33333333-c.png", "c"))));

        assertTrue(refused.getMessage().contains("3") && refused.getMessage().contains("2"),
                "the cap refusal names both the sent count and the cap");
        assertTrue(frames.calls.isEmpty());
    }
}
