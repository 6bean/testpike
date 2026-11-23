package com.sixbean.testpike.core.aichat.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.aichat.ChatImageAttachment;
import com.sixbean.testpike.common.aichat.ChatObjectRef;
import com.sixbean.testpike.common.mq.AiChatRunMessage;
import com.sixbean.testpike.core.aichat.api.dto.ChatFrame;
import com.sixbean.testpike.core.aichat.api.dto.ChatMessageInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionPageView;
import com.sixbean.testpike.core.aichat.api.dto.InjectLanding;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import com.sixbean.testpike.core.aichat.domain.service.impl.ChatSessionServiceImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared in-memory fakes of the dialog run door (AO batch S4 ,
 * single-sourced per the cross-chain duplication rule): the attachment
 * spectra test and the identity-channel spectra test drive the SAME
 * {@code ChatRunServiceImpl} through the same collaborators, so the fakes
 * live exactly once. Infrastructure isolation only — every collaborator here
 * is a test fixture, never a business mock.
 */
public final class ChatRunDoorFakes {

    /** Shared mapper for the delegation to the real whitelist validation point. */
    private static final ObjectMapper SHARED_MAPPER = new ObjectMapper();

    private ChatRunDoorFakes() {
    }

    /** Ownership door fake: one pinned session, everything else unsupported. */
    public static final class SessionDoor implements ChatSessionService {
        private final ChatSessionInfo session;

        /** Records every context fold so the run door's wiring is assertable. */
        public final List<String> contextApplied = new ArrayList<>();

        public SessionDoor(ChatSessionInfo session) {
            this.session = session;
        }

        @Override
        public ChatSessionInfo requireOwned(long sessionId, long userId) {
            return session;
        }

        @Override
        public ChatSessionInfo create(long projectId, long userId, String title,
                                      int source, String contextJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatSessionInfo findById(long sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatSessionInfo rename(long sessionId, long userId, String title) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatSessionInfo archive(long sessionId, long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void applyAutoTitle(long sessionId, String firstQuestion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatSessionPageView listVisible(long userId, List<Long> projectIds, Integer status,
                                               Integer source, String keyword, int page, int pageSize) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String normalizeContextWhitelist(String contextJson, long sessionProjectId) {
            // The real single validation point runs for real here (L2 rule:
            // business logic is never faked) — the DB handle is simply not
            // used by this pure pass, so a null template is honest isolation.
            return new ChatSessionServiceImpl(null, SHARED_MAPPER)
                    .normalizeContextWhitelist(contextJson, sessionProjectId);
        }

        @Override
        public void applyQuestionContext(long sessionId, String normalizedQuestionContext) {
            contextApplied.add(normalizedQuestionContext);
        }
    }

    /** Records every ledger call so "refusal lands no row" is assertable. */
    public static final class LedgerFrames implements ChatFrameService {
        public final List<String> calls = new ArrayList<>();

        @Override
        public long openMessage(long sessionId, String role, String content) {
            calls.add("open:" + role);
            return role.equals("user") ? 101L : 102L;
        }

        @Override
        public long openMessage(long sessionId, String role, String content, String questionContextJson) {
            if (questionContextJson == null) {
                return openMessage(sessionId, role, content);
            }
            calls.add("open:" + role + ":" + questionContextJson);
            return role.equals("user") ? 101L : 102L;
        }

        @Override
        public ChatMessageInfo closeMessage(long messageId, String status) {
            calls.add("close:" + status);
            return null;
        }

        @Override
        public String messageStatus(long messageId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatMessageInfo recordFeedback(long messageId, long userId, int feedback, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long appendFrame(long sessionId, long messageId, ChatFrameType type,
                                String payload, String metaJson) {
            // the door now books ledger frames (the user-image
            // frame rides the user turn), so the fake records the landing
            // instead of refusing it — payload and meta kept for assertions.
            calls.add("frame:" + type.wireName() + ":" + messageId + ":"
                    + (payload == null ? "" : payload)
                    + (metaJson == null || metaJson.isBlank() ? "" : "|" + metaJson));
            return 0L;
        }

        @Override
        public InjectLanding injectUserMessage(long sessionId, long userId, String content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ChatFrame> findInjectsAfter(long sessionId, long afterSeq) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long lastSeq(long sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ChatFrame> replayAfter(long sessionId, long cursor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ChatFrame> replayMessageBlock(long userId, long messageId, long cursor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ChatFrame> replayAfter(long userId, long sessionId, long cursor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ChatMessageInfo> listSealedMessages(long userId, long sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatMessageInfo abort(long messageId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatMessageInfo activeStreamingMessage(long userId, long sessionId) {
            throw new UnsupportedOperationException();
        }
    }

    /** Records the exact run messages the door publishes. */
    public static final class CapturingPublisher implements ChatRunPublisher {
        public final List<AiChatRunMessage> published = new ArrayList<>();

        @Override
        public void publishRun(AiChatRunMessage message) {
            published.add(message);
        }
    }
}
