package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatShareInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatShareSnapshotView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Read-only dialog snapshot shares : the share link hands a
 * colleague a frozen copy of one conversation without an account. Three
 * properties this test pins down, all straight from the design rules:
 * <ul>
 * <li>frozen at creation — the snapshot is the export pipeline's masked
 * output rendered once and stored, so credentials and PII never ride out
 * and later session changes (new turns, renames) never alter what an
 * existing link shows;</li>
 * <li>self-destruct — an expired link deletes its row on the next touch, and
 * a revoked link stops answering without deleting the audit row;</li>
 * <li>fail-closed doors — the token is a non-enumerable UUID (CI badge
 * posture, V59), garbage/unknown/expired/revoked all answer the same 404
 * sentence (the link face never reveals which tokens once existed), a
 * stranger cannot share someone else's session, and the owner id is the
 * only thing the create door trusts.</li>
 * </ul>
 * Real dev database, zero mocks (same posture as {@link
 * ChatSessionMarkdownExportTest}).
 */
@SpringBootTest(classes = CoreApplication.class)
class ChatShareSnapshotTest {

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private ChatFrameService frameService;

    @Autowired
    private ChatShareService shareService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newUserId(String tag) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, tag, tag);
    }

    private long newProjectId(String tag) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, tag, tag);
    }

    private ChatSessionInfo freshSession(String title, long userId, long projectId) {
        return sessionService.create(projectId, userId, title, 1, "{}");
    }

    private void sealTurn(long sessionId, String role, String content) {
        long messageId = frameService.openMessage(sessionId, role, content);
        frameService.closeMessage(messageId, "done");
    }

    @Test
    void shareFreezesMaskedSnapshotAtCreationTime() {
        String tag = "t438s" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long userId = newUserId(tag);
        ChatSessionInfo session = freshSession("分享试验", userId, newProjectId(tag));
        long sessionId = session.id();
        sealTurn(sessionId, "user", "查一下 13812345678 和 Bearer sk-secreta 的账号");
        sealTurn(sessionId, "assistant", "手机号在记录里，令牌请轮换。");

        ChatShareInfo share = shareService.create(userId, sessionId, 72);
        assertNotNull(share.shareToken());
        assertEquals(1, share.turnCount());
        // The snapshot row stores what the export door produced: the cleartext
        // phone and the bearer credential must NOT be anywhere inside it.
        String stored = jdbcTemplate.queryForObject(
                "SELECT snapshot_markdown FROM tp_ai_chat_share WHERE id = ?",
                String.class, share.id());
        assertFalse(stored.contains("13812345678"), "phone leaked into the frozen snapshot");
        assertFalse(stored.contains("sk-secreta"), "bearer credential leaked");
        assertTrue(stored.contains("手机号在记录里"), "answer text should survive masking");

        // The token door answers without any user id: a holder of the link,
        // not a member of the system, reads exactly the frozen copy.
        ChatShareSnapshotView view = shareService.openByToken(share.shareToken());
        assertEquals("分享试验", view.title());
        assertEquals(1, view.turnCount());
        assertFalse(view.markdown().contains("13812345678"));
    }

    @Test
    void sourceSessionChangesNeverAlterAFrozenSnapshot() {
        String tag = "t438f" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long userId = newUserId(tag);
        ChatSessionInfo session = freshSession("冻结语义", userId, newProjectId(tag));
        sealTurn(session.id(), "user", "第一个问题");
        sealTurn(session.id(), "assistant", "第一个回答");

        ChatShareInfo share = shareService.create(userId, session.id(), 24);
        String snapshotAtCreation = shareService.openByToken(share.shareToken()).markdown();

        // Later traffic and even a rename must not rewrite what the link shows:
        // the snapshot is a copy, not a view over the live ledger.
        sealTurn(session.id(), "user", "分享之后才问的新内容");
        sessionService.rename(session.id(), userId, "改名不影响快照");

        ChatShareSnapshotView after = shareService.openByToken(share.shareToken());
        assertEquals(snapshotAtCreation, after.markdown());
        assertFalse(after.markdown().contains("分享之后才问的新内容"));
        assertEquals("冻结语义", after.title());
    }

    @Test
    void expiredLinkSelfDestructsOnNextTouch() {
        String tag = "t438x" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long userId = newUserId(tag);
        ChatSessionInfo session = freshSession("过期即焚", userId, newProjectId(tag));
        sealTurn(session.id(), "user", "问题");
        sealTurn(session.id(), "assistant", "回答");
        ChatShareInfo share = shareService.create(userId, session.id(), 24);

        // Push the expiry into the past the way the clock would, then touch it.
        jdbcTemplate.update("UPDATE tp_ai_chat_share SET expires_at = now() - interval '1 hour' WHERE id = ?",
                share.id());
        ServiceException refusal = assertThrows(ServiceException.class,
                () -> shareService.openByToken(share.shareToken()));
        assertEquals(Integer.valueOf(BizErrorCode.NOT_FOUND), refusal.getCode());
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_ai_chat_share WHERE id = ?", Integer.class, share.id());
        assertEquals(0, remaining, "expired row must be deleted on the read touch (self-destruct)");
    }

    @Test
    void revokedLinkStopsAnsweringButKeepsTheAuditRow() {
        String tag = "t438r" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long userId = newUserId(tag);
        ChatSessionInfo session = freshSession("撤回链路", userId, newProjectId(tag));
        sealTurn(session.id(), "user", "问题");
        ChatShareInfo share = shareService.create(userId, session.id(), 24);

        ChatShareInfo revoked = shareService.revoke(userId, share.id());
        assertEquals(1, revoked.revoked());

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> shareService.openByToken(share.shareToken()));
        assertEquals(Integer.valueOf(BizErrorCode.NOT_FOUND), refusal.getCode());
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_ai_chat_share WHERE id = ?", Integer.class, share.id());
        assertEquals(1, remaining, "revocation is a state flip, never a delete");

        // Idempotent: re-revoking the same share answers the same view.
        assertEquals(1, shareService.revoke(userId, share.id()).revoked());
    }

    @Test
    void strangerCannotShareForeignSession() {
        String tag = "t438o" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long ownerId = newUserId(tag);
        long strangerId = newUserId(tag + "-b");
        ChatSessionInfo session = freshSession("他人会话", ownerId, newProjectId(tag));
        sealTurn(session.id(), "user", "只有属主能分享");

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> shareService.create(strangerId, session.id(), 24));
        assertEquals(Integer.valueOf(BizErrorCode.FORBIDDEN), refusal.getCode());

        // Revocation is owned the same way — a stranger cannot kill someone
        // else's live link.
        ChatShareInfo share = shareService.create(ownerId, session.id(), 24);
        ServiceException revokeRefusal = assertThrows(ServiceException.class,
                () -> shareService.revoke(strangerId, share.id()));
        assertEquals(Integer.valueOf(BizErrorCode.FORBIDDEN), revokeRefusal.getCode());
    }

    @Test
    void emptyDialogAndGarbageTokensAreRefusedWithNamedReasons() {
        String tag = "t438e" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long userId = newUserId(tag);
        ChatSessionInfo session = freshSession("空对话", userId, newProjectId(tag));

        // A conversation with no question turn yet has nothing worth sharing —
        // refuse at the door instead of freezing an empty shell.
        ServiceException refusal = assertThrows(ServiceException.class,
                () -> shareService.create(userId, session.id(), 24));
        assertEquals(Integer.valueOf(BizErrorCode.VALIDATION_ERROR), refusal.getCode());

        // Token hygiene on the public door: garbage answers 400, an
        // unknown-but-well-formed UUID answers the same 404 sentence as an
        // expired link (the link face never reveals which tokens once existed).
        ServiceException garbage = assertThrows(ServiceException.class,
                () -> shareService.openByToken("not-a-token"));
        assertEquals(Integer.valueOf(BizErrorCode.VALIDATION_ERROR), garbage.getCode());
        ServiceException unknown = assertThrows(ServiceException.class,
                () -> shareService.openByToken(UUID.randomUUID().toString()));
        assertEquals(Integer.valueOf(BizErrorCode.NOT_FOUND), unknown.getCode());
    }

    @Test
    void ttlIsClampedIntoTheSevenDayWindow() {
        String tag = "t438t" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long userId = newUserId(tag);
        ChatSessionInfo session = freshSession("时效窗口", userId, newProjectId(tag));
        sealTurn(session.id(), "user", "问题");
        sealTurn(session.id(), "assistant", "回答");

        ChatShareInfo over = shareService.create(userId, session.id(), 9999);
        long hoursToExpiry = java.time.Duration
                .between(java.time.LocalDateTime.now(), over.expiresAt()).toHours();
        assertTrue(hoursToExpiry <= 168 && hoursToExpiry > 166,
                "ttl beyond the window must clamp to 7 days, got " + hoursToExpiry + "h");

        ChatShareInfo under = shareService.create(userId, session.id(), 0);
        long minHours = java.time.Duration
                .between(java.time.LocalDateTime.now(), under.expiresAt()).toHours();
        assertTrue(minHours >= 0 && minHours < 2, "ttl below the window must clamp up to 1 hour");
    }
}
