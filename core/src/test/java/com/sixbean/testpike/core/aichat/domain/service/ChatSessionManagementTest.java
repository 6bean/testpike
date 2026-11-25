package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionListItemInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionPageView;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session management back plane : the list / rename / archive
 * contracts the dialog history panel consumes, plus the server-side
 * auto-title rule that seals the placeholder the dock opens with.
 * <p>What this pins on the real dev database with zero mocks (same posture as
 * {@code ChatFrameProtocolTest}):
 * <ul>
 * <li>a blank title is no longer a refusal — the session lands as a
 * placeholder and the FIRST user question names it (truncated at the
 * single shared bound so the list row and the auto-title never disagree);</li>
 * <li>a hand-typed title survives; the auto-title never overwrites it;</li>
 * <li>rename keeps the horizontal red line (AF-g): a stranger gets refused
 * with a named reason, blank/oversized titles are refused fail-closed;</li>
 * <li>archive keeps history (status flips, the row and its turns stay —
 * nothing is deleted) and is idempotent;</li>
 * <li>the list page is owner-scoped (a foreign session never shows), skips
 * empty sessions from the ledger count, filters by keyword / source /
 * project segment, and pages newest-activity first (S-group contract).</li>
 * </ul>
 * Fixtures create random-tagged users/projects so demo projects stay untouched.
 */
@SpringBootTest(classes = CoreApplication.class)
class ChatSessionManagementTest {

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private ChatFrameService frameService;

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

    private String tag(String stem) {
        return stem + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** A session that counts in the list: placeholder title + one sealed user turn. */
    private ChatSessionInfo sessionWithOneTurn(long projectId, long userId, int source, String question) {
        ChatSessionInfo session = sessionService.create(projectId, userId, null, source, "{}");
        long userMsg = frameService.openMessage(session.id(), "user", question);
        frameService.closeMessage(userMsg, "done");
        return session;
    }

    private List<Long> idsOf(ChatSessionPageView page) {
        return page.list().stream().map(ChatSessionListItemInfo::id).toList();
    }

    /**
 * Image-ledger replay door regression: probing a session nobody
 * ever opened must not fold a raw "empty result" DAO failure into the
 * generic internal error — the session service refuses with the business
 * NOT_FOUND, and the door inherits the same named refusal (ownership
 * runs before any frame read). First pinned live by the 2026-11-13
 */
    @Test
    void unknownSessionIdIsRefusedWithBusinessNotFoundThroughBothDoors() {
        long userId = newUserId(tag("t625u"));
        // Ghost band verified empty on the live dev db; a literal keeps the
        // probe deterministic without reading the global session max.
        long unknownSession = 999_999_999L;

        ServiceException read = assertThrows(ServiceException.class,
                () -> sessionService.findById(unknownSession));
        assertTrue(read.getMessage().contains("不存在"), read.getMessage());

        ServiceException door = assertThrows(ServiceException.class,
                () -> frameService.findChatImageMetas(userId, unknownSession, 5));
        assertTrue(door.getMessage().contains("不存在"), door.getMessage());
    }

    @Test
    void blankTitleLandsAsPlaceholderAndFirstQuestionNamesTheSession() {
        long userId = newUserId(tag("t461u"));
        long projectId = newProjectId(tag("t461p"));
        ChatSessionInfo session = sessionService.create(projectId, userId, null, 1, "{}");
        assertEquals("", session.title(), "a title-less open must land as the empty placeholder, not a refusal");

        String question = "帮我分析一下为什么昨晚的登录接口压测在第三轮全部超时，"
                + "并且顺带看看失败率曲线".repeat(5);
        long userMsg = frameService.openMessage(session.id(), "user", question);
        frameService.closeMessage(userMsg, "done");

        ChatSessionInfo sealed = sessionService.findById(session.id());
        assertEquals(60, sealed.title().length(), "auto-title cuts at the shared 60-char bound");
        assertEquals(question.strip().substring(0, 60), sealed.title());
    }

    @Test
    void autoTitleNeverOverwritesAHeldTitleOrFiresOnLaterTurns() {
        long userId = newUserId(tag("t461u"));
        long projectId = newProjectId(tag("t461p"));
        ChatSessionInfo session = sessionService.create(projectId, userId, "  压测复盘讨论  ", 1, "{}");
        assertEquals("压测复盘讨论", session.title(), "a hand title is stored stripped");

        sessionWithTurn(session.id(), "第一个问题");
        sessionWithTurn(session.id(), "第二个问题");

        assertEquals("压测复盘讨论", sessionService.findById(session.id()).title());
    }

    private void sessionWithTurn(long sessionId, String question) {
        long userMsg = frameService.openMessage(sessionId, "user", question);
        frameService.closeMessage(userMsg, "done");
    }

    @Test
    void renameHonoursOwnershipAndRefusesBlankOrOversizedTitles() {
        long userId = newUserId(tag("t461u"));
        long stranger = newUserId(tag("t461x"));
        long projectId = newProjectId(tag("t461p"));
        ChatSessionInfo session = sessionService.create(projectId, userId, null, 1, "{}");

        assertThrows(ServiceException.class, () -> sessionService.rename(session.id(), stranger, "偷改标题"));
        assertThrows(ServiceException.class, () -> sessionService.rename(session.id(), userId, "   "));
        assertThrows(ServiceException.class,
                () -> sessionService.rename(session.id(), userId, "超".repeat(201)));

        ChatSessionInfo renamed = sessionService.rename(session.id(), userId, "  夜间批失败归因  ");
        assertEquals("夜间批失败归因", renamed.title());
    }

    @Test
    void archiveKeepsTheHistoryAndIsIdempotent() {
        long userId = newUserId(tag("t461u"));
        long projectId = newProjectId(tag("t461p"));
        ChatSessionInfo session = sessionWithOneTurn(projectId, userId, 1, "归档前的问题");

        ChatSessionInfo archived = sessionService.archive(session.id(), userId);
        assertEquals(1, archived.status(), "archive flips the status — it never deletes the row");
        ChatSessionInfo again = sessionService.archive(session.id(), userId);
        assertEquals(1, again.status(), "re-archiving is idempotent, not an error");

        assertEquals(1, frameService.listSealedMessages(userId, session.id()).size(),
                "archived sessions keep every sealed turn (archive-not-delete)");
        long archivedCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_chat_session WHERE id = ?", Long.class, session.id());
        assertEquals(1L, archivedCount);
    }

    @Test
    void listIsOwnerScopedSkipsEmptySessionsAndPagesNewestFirst() {
        long userId = newUserId(tag("t461u"));
        long stranger = newUserId(tag("t461x"));
        long projectId = newProjectId(tag("t461p"));

        ChatSessionInfo empty = sessionService.create(projectId, userId, "空会话不入账", 1, "{}");
        ChatSessionInfo older = sessionWithOneTurn(projectId, userId, 1, "旧问题一");
        ChatSessionInfo newest = sessionWithOneTurn(projectId, userId, 1, "最新问题二");
        // Deterministic activity order: now() can land in the same second for
        // back-to-back inserts, so each thread's recency is stamped by one more
        // traffic frame appended in the order the assertions expect.
        sealAssistantTraffic(older);
        sealAssistantTraffic(newest);

        ChatSessionPageView page = sessionService.listVisible(userId, null, null, null, null, 1, 50);
        List<Long> mine = idsOf(page);
        assertFalse(mine.contains(empty.id()), "a session with zero messages never enters the list ledger");
        assertTrue(mine.contains(older.id()) && mine.contains(newest.id()));
        assertEquals(newest.id(), mine.get(0), "newest activity first");

        ChatSessionPageView strangerPage = sessionService.listVisible(stranger, null, null, null, null, 1, 50);
        assertFalse(idsOf(strangerPage).contains(newest.id()),
                "a foreign user never sees another user's conversations (horizontal red line)");
    }

    private long sessionIdOf(ChatSessionInfo info) {
        return info.id();
    }

    /** Append one more sealed assistant turn: deterministic activity recency stamping. */
    private void sealAssistantTraffic(ChatSessionInfo session) {
        sealedAssistant(session);
    }

    /** Seal a placeholder assistant turn so the session has real traffic. */
    private long sealedAssistant(ChatSessionInfo session) {
        long msg = frameService.openMessage(session.id(), "assistant", null);
        frameService.appendFrame(session.id(), msg, ChatFrameType.DELTA, "答案", null);
        frameService.closeMessage(msg, "done");
        return msg;
    }

    @Test
    void listFiltersByKeywordSourceAndProjectSlice() {
        long userId = newUserId(tag("t461u"));
        long projectA = newProjectId(tag("t461pa"));
        long projectB = newProjectId(tag("t461pb"));

        sessionWithOneTurn(projectA, userId, 1, "标签页签梳理");
        sessionWithOneTurn(projectA, userId, 2, "独立页深聊");
        sessionWithOneTurn(projectB, userId, 1, "另一项目的问题");

        List<Long> onlyA = idsOf(sessionService.listVisible(userId, List.of(projectA), null, null, null, 1, 50));
        assertEquals(2, onlyA.size(), "project-slice filter keeps only sessions of the asked slice");

        List<Long> dockOnly = idsOf(sessionService.listVisible(userId, List.of(projectA), null, 1, null, 1, 50));
        assertEquals(1, dockOnly.size(), "source filter keeps one entry surface");

        List<Long> byKeyword = idsOf(sessionService.listVisible(userId, null, null, null, "深聊", 1, 50));
        assertEquals(1, byKeyword.size(), "keyword matches the title, case-insensitive contains");

        ChatSessionPageView paged = sessionService.listVisible(userId, null, null, null, null, 1, 2);
        assertEquals(3, paged.total(), "total counts the whole filtered set, not just the window");
        assertEquals(2, paged.list().size());
        ChatSessionPageView secondPage = sessionService.listVisible(userId, null, null, null, null, 2, 2);
        assertEquals(1, secondPage.list().size());
    }

    @Test
    void archivedSessionsLeaveTheDefaultListAndShowOnlyInArchiveQuery() {
        long userId = newUserId(tag("t461u"));
        long projectId = newProjectId(tag("t461p"));
        ChatSessionInfo live = sessionWithOneTurn(projectId, userId, 1, "仍在列表");
        ChatSessionInfo parked = sessionWithOneTurn(projectId, userId, 1, "已经归档");
        sessionService.archive(parked.id(), userId);

        List<Long> active = idsOf(sessionService.listVisible(userId, null, 0, null, null, 1, 50));
        assertTrue(active.contains(live.id()));
        assertFalse(active.contains(parked.id()), "the default (active) page never shows archived rows");

        List<Long> archived = idsOf(sessionService.listVisible(userId, null, 1, null, null, 1, 50));
        assertTrue(archived.contains(parked.id()), "status=1 is how the recycle surface finds them back");
    }
}
