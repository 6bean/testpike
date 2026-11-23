package com.sixbean.testpike.core.notification.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.api.dto.NotificationInfo;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.support.NotificationFixtureCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class NotificationServiceTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private NotificationFixtureCleaner cleaner;
    private long userA;
    private long userB;
    private long userEmpty;
    /** Per-run suffix so dedup keys never collide with rows left behind by a crashed window. */
    private final String runToken = Long.toString(System.nanoTime());

    @BeforeEach
    void seedFixtures() {
        cleaner = NotificationFixtureCleaner.forTestRun(cleaner, jdbcTemplate);
        userA = cleaner.insertUser("notif-svc-a-" + runToken, "站内信主收件人", 1);
        userB = cleaner.insertUser("notif-svc-b-" + runToken, "站内信他人收件人", 1);
        userEmpty = cleaner.insertUser("notif-svc-c-" + runToken, "站内信零通知用户", 1);
    }

    @AfterEach
    void purgeFixtures() {
        cleaner.cleanup();
    }

    @Test
    void create_站内信落库字段完整() {
        long id = notificationService.create(new NotificationCreateInput(
                userA, 10L, "AI_GEN_FAILED", "AI 用例生成失败",
                "需求「用户登录」生成失败：模型调用超时，请稍后重试", "ai-gen-fail:" + runToken));

        NotificationInfo info = notificationService.get(id);
        assertNotNull(info);
        assertEquals(userA, info.userId());
        assertEquals(10L, info.projectId());
        assertEquals("AI_GEN_FAILED", info.eventType());
        assertEquals(0, info.readFlag());
        assertEquals(1, info.status());
        assertEquals("ai-gen-fail:" + runToken, info.dedupKey());
    }

    @Test
    void listByUser_按用户隔离_未读筛选() {
        notificationService.create(new NotificationCreateInput(userA, 10L, "AI_GEN_FAILED", "失败", "内容A", "k1-" + runToken));
        notificationService.create(new NotificationCreateInput(userA, 10L, "AI_GEN_DONE", "完成", "内容B", "k2-" + runToken));
        notificationService.create(new NotificationCreateInput(userB, 10L, "AI_GEN_FAILED", "他人", "内容C", "k3-" + runToken));

        PageResult<NotificationInfo> all = notificationService.listByUser(userA, null, 1, 10);
        assertEquals(2L, all.total());

        PageResult<NotificationInfo> unread = notificationService.listByUser(userA, 0, 1, 10);
        assertEquals(2L, unread.total());
    }

    @Test
    void markRead_已读标记生效_未读列表减少() {
        long id = notificationService.create(new NotificationCreateInput(
                userA, 10L, "AI_GEN_FAILED", "失败", "内容A", "k1-" + runToken));

        notificationService.markRead(id, userA);

        PageResult<NotificationInfo> unread = notificationService.listByUser(userA, 0, 1, 10);
        assertEquals(0L, unread.total());
        assertEquals(1, notificationService.get(id).readFlag());
    }

    @Test
    void create_同dedupKey_合并为一条() {
        String dupKey = "dup-1-" + runToken;
        notificationService.create(new NotificationCreateInput(userA, 10L, "AI_GEN_FAILED", "失败", "内容A", dupKey));
        notificationService.create(new NotificationCreateInput(userA, 10L, "AI_GEN_FAILED", "失败", "内容A", dupKey));

        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE dedup_key = ?", Long.class, dupKey);
        assertEquals(1L, count);
    }

    @Test
    void markRead_他人通知不可标记() {
        long id = notificationService.create(new NotificationCreateInput(
                userA, 10L, "AI_GEN_FAILED", "失败", "内容A", "k1-" + runToken));

        notificationService.markRead(id, userB);

        assertEquals(0, notificationService.get(id).readFlag());
    }


    @Test
    void listByUser_按事件类型筛选() {
        notificationService.create(new NotificationCreateInput(userA, 10L, "AI_GEN_FAILED", "失败", "内容A", "t1-" + runToken));
        notificationService.create(new NotificationCreateInput(userA, 10L, "AI_GEN_DONE", "完成", "内容B", "t2-" + runToken));
        notificationService.create(new NotificationCreateInput(userA, 10L, "AI_GEN_DONE", "完成", "内容C", "t3-" + runToken));

        PageResult<NotificationInfo> done = notificationService.listByUser(userA, null, 1, 10, "AI_GEN_DONE");
        assertEquals(2L, done.total());
        PageResult<NotificationInfo> failedUnread = notificationService.listByUser(userA, 0, 1, 10, "AI_GEN_FAILED");
        assertEquals(1L, failedUnread.total());
        assertEquals("AI_GEN_FAILED", failedUnread.list().get(0).eventType());
        assertEquals(3L, notificationService.listByUser(userA, null, 1, 10).total());
    }

    @Test
    void markAllRead_全部未读置已读_返回影响条数() {
        notificationService.create(new NotificationCreateInput(userA, 10L, "AI_GEN_FAILED", "失败", "内容A", "m1-" + runToken));
        notificationService.create(new NotificationCreateInput(userA, 10L, "AI_GEN_DONE", "完成", "内容B", "m2-" + runToken));
        long readId = notificationService.create(new NotificationCreateInput(userA, 10L, "AI_GEN_DONE", "完成", "内容C", "m3-" + runToken));
        notificationService.markRead(readId, userA);
        notificationService.create(new NotificationCreateInput(userB, 10L, "AI_GEN_DONE", "他人", "内容D", "m4-" + runToken));

        int affected = notificationService.markAllRead(userA);
        assertEquals(2, affected);
        assertEquals(0L, notificationService.listByUser(userA, 0, 1, 10).total());
        assertEquals(1, notificationService.get(readId).readFlag());
        NotificationInfo other = notificationService.listByUser(userB, null, 1, 10).list().get(0);
        assertEquals(0, other.readFlag());
    }

    @Test
    void markAllRead_幂等_重复调用不报错计数不重复() {
        notificationService.create(new NotificationCreateInput(userA, 10L, "AI_GEN_DONE", "完成", "内容A", "i1-" + runToken));
        notificationService.create(new NotificationCreateInput(userA, 10L, "AI_GEN_DONE", "完成", "内容B", "i2-" + runToken));

        assertEquals(2, notificationService.markAllRead(userA));
        assertEquals(0, notificationService.markAllRead(userA));
        Long readCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE user_id = ? AND read_flag = 1", Long.class, userA);
        assertEquals(2L, readCount);
    }

    @Test
    void markAllRead_无通知用户返回零不报错() {
        assertEquals(0, notificationService.markAllRead(userEmpty));
    }

    // ---- page 3: server-side sorting of the notification list.
    // Single-table read over tp_notification, so the sort lands as a
    // whitelisted ORDER BY column (same posture as the user/tag slices):
    // only mapped keys reach SQL, an off-list or injection-shaped key is
    // refused fail-closed with the allowed list named in plain words, and
    // the historical five-field call keeps the newest-first id DESC order.

    @Test
    void listByUser_不传排序保持id倒序_旧签名零行为变化() {
        notificationService.create(new NotificationCreateInput(userA, 10L, "AI_GEN_DONE", "甲", "内容A", "s1-" + runToken));
        notificationService.create(new NotificationCreateInput(userA, 10L, "AI_GEN_FAILED", "乙", "内容B", "s2-" + runToken));
        notificationService.create(new NotificationCreateInput(userA, 10L, "AI_GEN_DONE", "丙", "内容C", "s3-" + runToken));

        PageResult<NotificationInfo> legacy = notificationService.listByUser(userA, null, 1, 10);
        PageResult<NotificationInfo> explicitNull = notificationService.listByUser(userA, null, 1, 10, null, null, null);
        assertEquals(legacy.list().stream().map(NotificationInfo::id).toList(),
                explicitNull.list().stream().map(NotificationInfo::id).toList(),
                "null sortBy 必须与历史倒序完全一致");
        List<Long> ids = legacy.list().stream().map(NotificationInfo::id).toList();
        assertEquals(ids.stream().sorted(java.util.Comparator.reverseOrder()).toList(), ids,
                "默认序=newest-first（id 倒序）");
    }

    @Test
    void listByUser_事件类型与标题双向镜像排序() {
        notificationService.create(new NotificationCreateInput(userA, 10L, "BATCH_FINISHED", "zzz 尾部", "内容A", "s4-" + runToken));
        notificationService.create(new NotificationCreateInput(userA, 10L, "AI_GEN_DONE", "mmm 中部", "内容B", "s5-" + runToken));
        notificationService.create(new NotificationCreateInput(userA, 10L, "AI_GEN_FAILED", "aaa 头部", "内容C", "s6-" + runToken));

        List<String> typeAsc = notificationService.listByUser(userA, null, 1, 10, null, "event_type", "asc")
                .list().stream().map(NotificationInfo::eventType).toList();
        List<String> typeDesc = notificationService.listByUser(userA, null, 1, 10, null, "event_type", "desc")
                .list().stream().map(NotificationInfo::eventType).toList();
        assertEquals(typeAsc.stream().sorted().toList(), typeAsc, "event_type asc 必须整体升序");
        assertEquals(typeAsc.reversed(), typeDesc, "desc 必须是 asc 的镜像");

        List<String> titleAsc = notificationService.listByUser(userA, null, 1, 10, null, "title", "asc")
                .list().stream().map(NotificationInfo::title).toList();
        assertEquals(List.of("aaa 头部", "mmm 中部", "zzz 尾部"), titleAsc, "title asc 按标题字典序");
    }

    @Test
    void listByUser_读旗标与创建时间排序生效() {
        long readId = notificationService.create(new NotificationCreateInput(userA, 10L, "AI_GEN_DONE", "已读条", "内容A", "s7-" + runToken));
        notificationService.create(new NotificationCreateInput(userA, 10L, "AI_GEN_DONE", "未读条", "内容B", "s8-" + runToken));
        notificationService.markRead(readId, userA);

        List<Integer> flags = notificationService.listByUser(userA, null, 1, 10, null, "read_flag", "asc")
                .list().stream().map(NotificationInfo::readFlag).toList();
        assertEquals(flags.stream().sorted().toList(), flags, "read_flag asc：已读(1)在前未读(0)在后");

        // Distinct created_at stamps so the time order differs from id order.
        jdbcTemplate.update("UPDATE tp_notification SET created_at = now() - interval '2 day' WHERE dedup_key = ?",
                "s7-" + runToken);
        List<String> timeAsc = notificationService.listByUser(userA, null, 1, 10, null, "created_at", "asc")
                .list().stream().map(NotificationInfo::title).toList();
        assertEquals(List.of("已读条", "未读条"), timeAsc, "created_at asc 按时间递增（旧时刻在前）");
    }

    @Test
    void listByUser_排序翻页延续序不重复不丢行() {
        for (int i = 0; i < 5; i++) {
            notificationService.create(new NotificationCreateInput(userA, 10L, "AI_GEN_DONE", "排序条" + i, "内容" + i, "sp" + i + "-" + runToken));
        }
        List<String> full = notificationService.listByUser(userA, null, 1, 10, null, "title", "asc")
                .list().stream().map(NotificationInfo::title).toList();
        List<String> window = new ArrayList<>(notificationService.listByUser(userA, null, 1, 2, null, "title", "asc")
                .list().stream().map(NotificationInfo::title).toList());
        window.addAll(notificationService.listByUser(userA, null, 2, 2, null, "title", "asc")
                .list().stream().map(NotificationInfo::title).toList());
        assertEquals(full.subList(0, 4), window, "翻页窗口必须延续服务端排序序而非缺省倒序");
    }

    @Test
    void listByUser_列外与注入排序键拒收并报允许清单() {
        ServiceException thrown = assertThrows(ServiceException.class,
                () -> notificationService.listByUser(userA, null, 1, 10, null, "created_at; DROP TABLE tp_notification", null));
        assertTrue(thrown.getMessage().contains("event_type"),
                "报错须点名允许清单，实收: " + thrown.getMessage());
        assertThrows(ServiceException.class,
                () -> notificationService.listByUser(userA, null, 1, 10, null, "not_a_column", "asc"));
        // fail-closed: the table stays intact (the injection never reached SQL)
        Long intact = jdbcTemplate.queryForObject("SELECT count(*) FROM tp_notification", Long.class);
        assertNotNull(intact);
    }
}
