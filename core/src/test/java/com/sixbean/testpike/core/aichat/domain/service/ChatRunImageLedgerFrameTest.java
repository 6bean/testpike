package com.sixbean.testpike.core.aichat.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.aichat.ChatImageAttachment;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.service.impl.ChatRunServiceImpl;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.executor.domain.component.InMemoryConfigServiceStub;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cross-message image ledger contract :
 * every question that carries user images books ONE state frame on its own
 * user turn, before that turn is sealed — the durable proof of which
 * objectKeys the question shipped with, so a later turn of the same session
 * can replay the pictures from the ledger instead of asking the user to
 * paste again.
 * <p>Display posture (red line, zero visual drift): user rows carry no
 * process area in the dock reducer and history reload rebuilds bodies for
 * assistant rows only, so this frame never renders to the user — it is the
 * machine ledger, payload first (a human-readable sentence for anyone
 * reading the ledger directly), structured value in meta_json.chatImages
 * (the dataMoment precedent: structured meta, no new frame type, no schema
 * migration).
 * <p>A photoless run keeps its exact old shape: no frame row, no seq burn.
 * L2 posture: in-memory door fakes; the real admin→core→MQ→ai-service
 * replay leg lands on the E2E ladder (assertion ①).
 */
class ChatRunImageLedgerFrameTest {

    private static final long SESSION_ID = 9L;
    private static final long USER_ID = 7L;
    private static final long PROJECT_ID = 7L;

    private static ChatSessionInfo session() {
        return new ChatSessionInfo(SESSION_ID, PROJECT_ID, USER_ID, "t", 0, null, 1, 0L, 0L);
    }

    private static ChatRunServiceImpl service(ChatRunDoorFakes.LedgerFrames frames) {
        ConfigService config = new InMemoryConfigServiceStub(Map.of());
        return new ChatRunServiceImpl(new ChatRunDoorFakes.SessionDoor(session()), frames,
                new ChatRunDoorFakes.CapturingPublisher(), config, new ObjectMapper());
    }

    @Test
    void imageLedgerFrameLandsOnTheUserTurnBeforeSealing() {
        ChatRunDoorFakes.LedgerFrames frames = new ChatRunDoorFakes.LedgerFrames();
        List<ChatImageAttachment> raw = List.of(
                new ChatImageAttachment("tp/7/chat-image/202610/aaaa1111-shot.png", "  shot.png  "),
                new ChatImageAttachment("tp/7/chat-image/202610/bbbb2222-bug.jpg", null));

        service(frames).openRun(SESSION_ID, USER_ID, "看看这两张图", List.of(), raw);

        assertEquals(List.of(
                "open:user",
                "frame:state:101:本问附带图片 2 枚"
                        + "|{\"chatImages\":[{\"objectKey\":\"tp/7/chat-image/202610/aaaa1111-shot.png\","
                        + "\"fileName\":\"shot.png\"},"
                        + "{\"objectKey\":\"tp/7/chat-image/202610/bbbb2222-bug.jpg\","
                        + "\"fileName\":\"\"}]}",
                "close:done",
                "open:assistant"),
                frames.calls,
                "canonical images ride ONE state frame on the user row, booked BEFORE the seal");
    }

    @Test
    void photolessRunBooksNoLedgerFrame() {
        ChatRunDoorFakes.LedgerFrames frames = new ChatRunDoorFakes.LedgerFrames();

        service(frames).openRun(SESSION_ID, USER_ID, "纯文字问题", List.of(), List.of());

        assertEquals(List.of("open:user", "close:done", "open:assistant"), frames.calls,
                "no images = the exact old run shape, zero frame rows");
    }
}
