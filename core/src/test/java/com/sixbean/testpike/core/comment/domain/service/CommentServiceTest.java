package com.sixbean.testpike.core.comment.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.comment.api.dto.CommentCreateInput;
import com.sixbean.testpike.core.comment.api.dto.CommentInfo;
import com.sixbean.testpike.core.comment.api.dto.CommentUnreadItem;
import com.sixbean.testpike.core.comment.domain.service.CommentService;
import com.sixbean.testpike.core.notification.api.dto.NotificationInfo;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = com.sixbean.testpike.core.CoreApplication.class)
class CommentServiceTest {

    @Autowired
    private CommentService commentService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long author;
    private long mentionTarget;

    // Fixture-scoped teardown : comment/read/attachment are
    // non-project tables, so the channel is per-test id watermarks. Crashed-run
    // residue is dropped by this suite's fingerprints (author user family plus
    // the unique system-comment key defect/66/task:9001 asserted below) before
    // the floors are captured; the old blanket TRUNCATE rewound shared
    // sequences and wiped comment rows owned by concurrent writers.
    private long commentFloor;
    private long attachFloor;
    private long readFloor;

    @BeforeEach
    void seed() {
        sweepFamilyResidue();
        commentFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_comment");
        attachFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_comment_attachment");
        readFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_comment_read");
        cleanSharedUsersAndMentions();
        author = insertUser("v14_author");
        mentionTarget = insertUser("v14_mentionee");
    }

    @AfterEach
    void sweepRunRows() {
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_comment_attachment", attachFloor);
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_comment_read", readFloor);
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_comment", commentFloor);
        cleanSharedUsersAndMentions();
    }

    private void cleanSharedUsersAndMentions() {
        jdbcTemplate.update("DELETE FROM tp_notification WHERE event_type = 'MENTION'");
        jdbcTemplate.update("DELETE FROM tp_user_role WHERE user_id IN"
                + " (SELECT id FROM tp_user WHERE username LIKE 'v14_%')");
        jdbcTemplate.update("DELETE FROM tp_user WHERE username LIKE 'v14_%'");
    }

    private void sweepFamilyResidue() {
        // tp_comment_read is keyed by (user, host), not by comment — the
        // residue channel for it is this suite's author user family itself.
        String familyUsers = "SELECT id FROM tp_user WHERE username LIKE 'v14_%'";
        jdbcTemplate.update("DELETE FROM tp_comment_read WHERE user_id IN (" + familyUsers + ")");
        String ownedComments = "author_id IN (" + familyUsers + ")"
                + " OR (host_type = 'defect' AND host_id = 66 AND ref_key = 'task:9001')";
        jdbcTemplate.update("DELETE FROM tp_comment_attachment WHERE comment_id IN"
                + " (SELECT id FROM tp_comment WHERE " + ownedComments + ")");
        jdbcTemplate.update("DELETE FROM tp_comment WHERE " + ownedComments);
    }

    private long insertUser(String username) {
        Long uid = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, username, username);
        return uid != null ? uid : -1L;
    }

    private long create(long hostId, String hostType, String content, List<Long> mentions) {
        return commentService.create(author, new CommentCreateInput(hostType, hostId, content, mentions));
    }

    @Test
    void 四态宿主创建列表往返_作者名join() {
        for (String host : List.of("review", "case", "batch", "defect")) {
            long id = create(100L, host, host + " 宿主评论", List.of());
            List<CommentInfo> listed = commentService.list(host, 100L);
            assertEquals(1, listed.size(), host + " 宿主应恰一条");
            CommentInfo info = listed.get(0);
            assertEquals(id, info.id());
            assertEquals(host, info.hostType());
            assertEquals(1, info.source(), "人工评论 source=1");
            assertEquals(author, info.authorId());
            assertEquals("v14_author", info.authorName(), "作者用户名 join（角标/面板展示）");
            assertTrue(info.createdAt() != null);
        }
    }

    @Test
    void 非法宿主400_正文校验() {
        ServiceException host = assertThrows(ServiceException.class,
                () -> create(1L, "foo", "越界宿主", List.of()));
        assertTrue(host.getMessage().contains("宿主"), "人话提示: " + host.getMessage());
        assertThrows(ServiceException.class, () -> create(1L, "case", "  ", List.of()));
        assertThrows(ServiceException.class, () -> create(1L, "case", "x".repeat(2001), List.of()));
        assertEquals(0, commentService.list("case", 1L).size(), "全部 400 不得半落库");
    }

    @Test
    void 提及站内信_自提及跳过_幽灵剔除() {
        long id = create(7L, "defect", "请看这个问题 @v14_mentionee @v14_author",
                List.of(mentionTarget, author, 999999L));
        Long targetGot = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_notification WHERE user_id = ? AND event_type = 'MENTION'"
                        + " AND dedup_key = ?", Long.class, mentionTarget, "comment:" + id + ":mention:" + mentionTarget);
        assertEquals(1, targetGot, "被提及者收 MENTION 站内信，dedup_key=comment:{id}:mention:{uid}");
        Long selfGot = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_notification WHERE user_id = ? AND event_type = 'MENTION'",
                Long.class, author);
        assertEquals(0, selfGot, "自提及不通知自己");
        Long ghostGot = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_notification WHERE user_id = ?", Long.class, 999999L);
        assertEquals(0, ghostGot, "tp_user 不存在的幽灵提及剔除（不炸主链）");
    }

    @Test
    void 未读角标_单SQL多宿主_已读清零_新评论再计() {
        create(11L, "case", "a1", List.of());
        create(11L, "case", "a2", List.of());
        create(12L, "case", "b1", List.of());
        List<CommentUnreadItem> unread = commentService.unreadCounts(mentionTarget, "case", List.of(11L, 12L, 13L));
        assertEquals(2, unread.size(), "无数据宿主 13 不出现（禁刷 0 行）");
        assertEquals(2, unread.stream().filter(i -> i.hostId() == 11L).findFirst().orElseThrow().unreadCount());
        assertEquals(1, unread.stream().filter(i -> i.hostId() == 12L).findFirst().orElseThrow().unreadCount());

        commentService.markRead(mentionTarget, "case", 11L);
        commentService.markRead(mentionTarget, "case", 11L);
        Long readRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_comment_read WHERE user_id = ? AND host_id = 11",
                Long.class, mentionTarget);
        assertEquals(1, readRows, "重复 mark-read 单行（UNIQUE 读位）");
        assertTrue(commentService.unreadCounts(mentionTarget, "case", List.of(11L)).isEmpty(), "已读清零");

        create(11L, "case", "a3 新评论", List.of());
        List<CommentUnreadItem> after = commentService.unreadCounts(mentionTarget, "case", List.of(11L));
        assertEquals(1, after.get(0).unreadCount(), "读位后新评论重新计未读（比较法）");
    }

    @Test
    void 无评论宿主markRead_读位落0_角标仍零() {
        commentService.markRead(mentionTarget, "batch", 555L);
        assertTrue(commentService.unreadCounts(mentionTarget, "batch", List.of(555L)).isEmpty());
        create(555L, "batch", "新批次评论", List.of());
        assertEquals(1, commentService.unreadCounts(mentionTarget, "batch", List.of(555L)).get(0).unreadCount());
    }

    @Test
    void 系统评论source2可写_T232追评链路同表() {
        jdbcTemplate.update("INSERT INTO tp_comment (host_type, host_id, source, content, ref_key)"
                + " VALUES ('defect', 66, 2, '系统追评', 'task:9001')");
        List<CommentInfo> listed = commentService.list("defect", 66L);
        assertEquals(1, listed.size());
        assertEquals(2, listed.get(0).source());
        assertEquals("task:9001", listed.get(0).refKey());
        assertTrue(listed.get(0).authorName() == null, "系统评论 author 可空");
    }
}
