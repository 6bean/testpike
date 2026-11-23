package com.sixbean.testpike.core.aichat.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.mq.AiChatRunMessage;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.service.impl.ChatRunServiceImpl;
import com.sixbean.testpike.core.executor.domain.component.InMemoryConfigServiceStub;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 spectra of the vertical identity channel inside the run door
 * : the system-manager verdict is decided once on
 * the admin session face (SystemManagerRoles single source) and lands on the
 * run message verbatim — core never re-reads the session, the broker carries
 * a DTO field instead of a lookup race, and a request without the new field
 * keeps the old shape (member default = false, zero drift for old clients).
 * <p>Infrastructure isolation only (L2 rule): collaborators are in-memory
 * fakes; the real admin→core→MQ→ai-service chain is proven by L3.
 */
class ChatRunIdentityChannelTest {

    private static final long SESSION_ID = 9L;
    private static final long USER_ID = 7L;
    private static final long PROJECT_ID = 3L;





    private static ChatRunServiceImpl service(ChatRunDoorFakes.CapturingPublisher publisher) {
        return new ChatRunServiceImpl(
                new ChatRunDoorFakes.SessionDoor(new ChatSessionInfo(SESSION_ID, PROJECT_ID,
                        USER_ID, "t", 0, null, 1, 0L, 0L)),
                new ChatRunDoorFakes.LedgerFrames(), publisher,
                new InMemoryConfigServiceStub(Map.of()), new ObjectMapper());
    }

    @Test
    void managerVerdictLandsOnTheRunMessageVerbatim() {
        ChatRunDoorFakes.CapturingPublisher publisher = new ChatRunDoorFakes.CapturingPublisher();

        service(publisher).openRun(SESSION_ID, USER_ID, "这个项目谁负责哪些缺陷",
                List.of(), List.of(), true);

        assertEquals(1, publisher.published.size());
        assertTrue(publisher.published.get(0).systemManager(),
                "the verdict decided on the admin face must reach the worker untouched");
    }

    @Test
    void memberVerdictAndLegacyDefaultBothRideAsFalse() {
        ChatRunDoorFakes.CapturingPublisher publisher = new ChatRunDoorFakes.CapturingPublisher();
        ChatRunServiceImpl service = service(publisher);

        service.openRun(SESSION_ID, USER_ID, "最近坑在哪", List.of(), List.of(), false);
        // Legacy five-argument door call (old wire shape): defaults to the
        // member view, never to the manager view — fail-closed on identity.
        service.openRun(SESSION_ID, USER_ID, "最近坑在哪", List.of(), List.of());

        assertEquals(2, publisher.published.size());
        assertFalse(publisher.published.get(0).systemManager());
        assertFalse(publisher.published.get(1).systemManager(),
                "an absent verdict field must default to the narrower (member) view");
    }
}
