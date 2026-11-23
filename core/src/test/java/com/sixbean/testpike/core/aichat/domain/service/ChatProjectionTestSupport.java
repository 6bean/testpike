package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Shared fixture helpers for the chat read-projection tests (draft cards,
 * goal-plan cards and their successors). User/project insertion, tag
 * generation, sealed-turn scaffolding and the session-owner lookup are
 * identical across those suites, so they are written exactly once here and
 * consumed as plain static calls with the suite's own {@code JdbcTemplate}
 * passed in (composition over inheritance: the Spring context still belongs
 * to each test class).
 *
 * <p>Fixtures use random-suffixed tags so the demo projects and shared seed
 * rows stay untouched.</p>
 */
final class ChatProjectionTestSupport {

    private ChatProjectionTestSupport() {
    }

    /** Underscore-free random suffix behind a stem, e.g. {@code drfu7c3a91ab2f0}. */
    static String randomTag(JdbcTemplate jdbc, String stem) {
        return stem + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** A status-1 account row with the given name reused for username and display name. */
    static long insertUser(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, name, name);
    }

    /** A status-1 project row where name and code share the same tag. */
    static long insertProject(JdbcTemplate jdbc, String code) {
        return jdbc.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    /** The owner id recorded on the session row (services take it as a plain id). */
    static long ownerOf(JdbcTemplate jdbc, long sessionId) {
        return jdbc.queryForObject(
                "SELECT user_id FROM tp_ai_chat_session WHERE id = ?", Long.class, sessionId);
    }

    /** One owned session whose sealed assistant answer carries a landed draft. */
    static ChatSessionInfo sessionWithLandedDraft(ChatFrameService frameService,
                                                  ChatSessionService sessionService,
                                                  JdbcTemplate jdbc, String metaJson) {
        long userId = insertUser(jdbc, randomTag(jdbc, "drfu"));
        ChatSessionInfo session = sessionService.create(
                insertProject(jdbc, randomTag(jdbc, "drfp")), userId, "草案读投影", 1, "{}");
        long message = frameService.openMessage(session.id(), "assistant", null);
        frameService.appendFrame(session.id(), message,
                ChatFrameType.DRAFT, "自进化提案", metaJson);
        frameService.closeMessage(message, "done");
        return session;
    }

    /** One owned session with a sealed user echo + a sealed assistant answer. */
    static ChatSessionInfo sessionWithSealedAnswer(ChatFrameService frameService,
                                                   ChatSessionService sessionService,
                                                   JdbcTemplate jdbc) {
        long userId = insertUser(jdbc, randomTag(jdbc, "gpfu"));
        ChatSessionInfo session = sessionService.create(
                insertProject(jdbc, randomTag(jdbc, "gfpf")), userId, "目标账本读投影", 1, "{}");
        long userTurn = frameService.openMessage(session.id(), "user", "分三步帮我做");
        frameService.closeMessage(userTurn, "done");
        long answerTurn = frameService.openMessage(session.id(), "assistant", "好的，先做第一步");
        frameService.closeMessage(answerTurn, "done");
        return session;
    }
}
