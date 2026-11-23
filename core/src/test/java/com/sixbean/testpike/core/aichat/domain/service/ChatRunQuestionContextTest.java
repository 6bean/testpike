package com.sixbean.testpike.core.aichat.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiChatRunMessage;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.service.impl.ChatRunServiceImpl;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.executor.domain.component.InMemoryConfigServiceStub;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 spectra of the per-question context channel inside the run door
 * : every question carries the page anchor it was
 * asked from — validated through the ONE whitelist door the session shell
 * owns (R51 single reconciliation point, never a second validator), journalled
 * on the user ledger row for per-question audit, and folded into the broker
 * message before the session-level pointer. Malformed keys, nested values and
 * foreign project slices are refused BEFORE any ledger row lands (refuse
 * first, journal after); an absent context keeps the exact old run shape
 * (zero drift for old callers).
 * <p>Infrastructure isolation only (L2 rule): the three collaborators are
 * in-memory fakes; the real admin→core→MQ chain is proven by L3.
 */
class ChatRunQuestionContextTest {

    private static final long SESSION_ID = 9L;
    private static final long USER_ID = 7L;
    private static final long PROJECT_ID = 7L;

    private static ChatSessionInfo sessionWith(String contextJson) {
        return new ChatSessionInfo(SESSION_ID, PROJECT_ID, USER_ID, "t", 0, contextJson, 1, 0L, 0L);
    }

    private static ChatRunServiceImpl service(ChatRunDoorFakes.CapturingPublisher publisher,
                                              ChatRunDoorFakes.LedgerFrames frames,
                                              ChatSessionInfo session) {
        return new ChatRunServiceImpl(new ChatRunDoorFakes.SessionDoor(session), frames,
                publisher, new InMemoryConfigServiceStub(Map.of()), new ObjectMapper());
    }

    @Test
    void legalContextIsJournalledAndItsPointerOverridesTheSession() {
        ChatRunDoorFakes.CapturingPublisher publisher = new ChatRunDoorFakes.CapturingPublisher();
        ChatRunDoorFakes.LedgerFrames frames = new ChatRunDoorFakes.LedgerFrames();
        ChatRunServiceImpl service = service(publisher, frames,
                sessionWith("{\"executionId\":99}"));

        service.openRun(SESSION_ID, USER_ID, "这个执行为什么失败", List.of(), List.of(),
                false, "{\"route\":\"/executions\",\"objectId\":42,\"executionId\":555}");

        assertEquals(List.of("open:user:{\"route\":\"/executions\",\"objectId\":42,\"executionId\":555}",
                "close:done", "open:assistant"), frames.calls,
                "the validated context journals on the user row only; the assistant row keeps the bare shape");
        AiChatRunMessage message = publisher.published.get(0);
        assertEquals(555L, message.executionId(), "this question's pointer folds ahead of the session-level one");
    }

    @Test
    void unknownKeyIsRefusedBeforeAnyLedgerRowLands() {
        ChatRunDoorFakes.CapturingPublisher publisher = new ChatRunDoorFakes.CapturingPublisher();
        ChatRunDoorFakes.LedgerFrames frames = new ChatRunDoorFakes.LedgerFrames();
        ChatRunServiceImpl service = service(publisher, frames, sessionWith(null));

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> service.openRun(SESSION_ID, USER_ID, "你好", List.of(), List.of(),
                        false, "{\"token\":\"abc\"}"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, refusal.getCode().intValue());
        assertTrue(refusal.getMessage().contains("token"), "the refusal names the offending key");
        assertTrue(frames.calls.isEmpty(), "a refused question leaves no half-written turn behind");
        assertTrue(publisher.published.isEmpty(), "a refused question never reaches the broker");
    }

    @Test
    void absentContextKeepsTheOldRunShape() {
        ChatRunDoorFakes.CapturingPublisher publisher = new ChatRunDoorFakes.CapturingPublisher();
        ChatRunDoorFakes.LedgerFrames frames = new ChatRunDoorFakes.LedgerFrames();
        ChatRunServiceImpl service = service(publisher, frames, sessionWith("{\"executionId\":99}"));

        service.openRun(SESSION_ID, USER_ID, "旧版调用", List.of(), List.of());

        assertEquals(List.of("open:user", "close:done", "open:assistant"), frames.calls,
                "no context field: the bare ledger doors are called exactly as before");
        assertEquals(99L, publisher.published.get(0).executionId(),
                "with no per-question pointer the session-level one still folds (T429 knife 1 intact)");
    }

    @Test
    void acceptedContextRefreshesTheSessionAnchorsAfterTheLedgerRowsLand() {
        ChatRunDoorFakes.SessionDoor door =
                new ChatRunDoorFakes.SessionDoor(sessionWith("{\"executionId\":99}"));
        ChatRunDoorFakes.CapturingPublisher publisher = new ChatRunDoorFakes.CapturingPublisher();
        ChatRunDoorFakes.LedgerFrames frames = new ChatRunDoorFakes.LedgerFrames();
        ChatRunServiceImpl service = new ChatRunServiceImpl(door, frames, publisher,
                new InMemoryConfigServiceStub(Map.of()), new ObjectMapper());

        service.openRun(SESSION_ID, USER_ID, "这个执行为什么失败", List.of(), List.of(),
                false, "{\"route\":\"/executions\",\"executionId\":555}");

        assertEquals(List.of("{\"route\":\"/executions\",\"executionId\":555}"), door.contextApplied,
                "the validated anchor is folded into the session context (slice 1b door)");
        assertFalse(frames.calls.isEmpty(), "the refresh happens after the turn rows landed");
    }

    @Test
    void absentContextNeverTouchesTheSessionContext() {
        ChatRunDoorFakes.SessionDoor door = new ChatRunDoorFakes.SessionDoor(sessionWith(null));
        ChatRunServiceImpl service = new ChatRunServiceImpl(door, new ChatRunDoorFakes.LedgerFrames(),
                new ChatRunDoorFakes.CapturingPublisher(), new InMemoryConfigServiceStub(Map.of()),
                new ObjectMapper());

        service.openRun(SESSION_ID, USER_ID, "旧版调用", List.of(), List.of());

        assertTrue(door.contextApplied.isEmpty(), "old callers keep the exact old session row");
    }

    @Test
    void foreignProjectSliceIsRefused() {
        ChatRunDoorFakes.CapturingPublisher publisher = new ChatRunDoorFakes.CapturingPublisher();
        ChatRunDoorFakes.LedgerFrames frames = new ChatRunDoorFakes.LedgerFrames();
        ChatRunServiceImpl service = service(publisher, frames, sessionWith(null));

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> service.openRun(SESSION_ID, USER_ID, "你好", List.of(), List.of(),
                        false, "{\"projectId\":123,\"route\":\"/cases\"}"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, refusal.getCode().intValue());
        assertTrue(refusal.getMessage().contains("跨项目"), "the refusal says which slice was crossed");
        assertTrue(frames.calls.isEmpty());
        assertNull(publisher.published.isEmpty() ? null : "unexpected", "never published");
    }
}
