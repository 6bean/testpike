package com.sixbean.testpike.core.notification.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.api.dto.NotificationPrefItem;
import com.sixbean.testpike.core.notification.api.dto.NotificationPrefToggle;
import com.sixbean.testpike.core.notification.domain.component.NotificationEventDict;
import com.sixbean.testpike.core.support.NotificationFixtureCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class NotificationPrefServiceTest {

    @Autowired
    private NotificationPrefService prefService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Preference-domain users tracked by the ledger; runToken keeps dedup keys crash-unique. */
    private NotificationFixtureCleaner cleaner;
    private long userA;
    private long userB;
    private final String runToken = Long.toString(System.nanoTime());

    @BeforeEach
    void seedFixtures() {
        cleaner = NotificationFixtureCleaner.forTestRun(cleaner, jdbcTemplate);
        userA = cleaner.insertUser("p5_alice-" + System.nanoTime(), "p5_alice", 1);
        userB = cleaner.insertUser("p5_bob-" + System.nanoTime(), "p5_bob", 1);
    }

    @AfterEach
    void purgeFixtures() {
        cleaner.cleanup();
    }

    private Map<String, NotificationPrefItem> asMap(List<NotificationPrefItem> items) {
        return items.stream().collect(Collectors.toMap(
                NotificationPrefItem::eventType, Function.identity()));
    }

    private long notifCount(long userId, String eventType) {
        Long c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_notification WHERE user_id = ? AND event_type = ?",
                Long.class, userId, eventType);
        return c == null ? 0 : c;
    }


    @Test
    void listNoRowsMeansDefaultSubscription() {
        List<NotificationPrefItem> items = prefService.listPrefs(userA);
        Map<String, NotificationPrefItem> map = asMap(items);

        for (String type : NotificationEventDict.all().keySet()) {
            assertNotNull(map.get(type), "GET 应含字典事件 " + type);
        }
        assertTrue(map.get("DEFECT_CLOSED").enabled(), "无记录=默认订阅");
        assertFalse(map.get("DEFECT_CLOSED").forced());
        assertFalse(map.get("DEFECT_CLOSED").defaultOff());
        assertTrue(map.get("LOGIN_LOCKED").forced(), "LOGIN_LOCKED 强制订阅标记");
        assertTrue(map.get("LOGIN_SESSION_REVOKE").forced(), "R6 会话吊销强制订阅标记");
        assertTrue(map.get("REVIEW_TIMEOUT").forced(), "Q4 评审超时强制订阅标记");
        assertFalse(map.get("EXECUTOR_ONLINE").enabled(), "default_off 首次即不订阅");
        assertTrue(map.get("EXECUTOR_ONLINE").defaultOff());
        assertFalse(map.get("DAILY_DIGEST").enabled(), "每日摘要 default_off");
    }

    @Test
    void updatePersistsAndReloadReflects() {
        prefService.updatePrefs(userA, List.of(
                new NotificationPrefToggle("DEFECT_CLOSED", false),
                new NotificationPrefToggle("EXECUTOR_ONLINE", true)));

        Map<String, NotificationPrefItem> map = asMap(prefService.listPrefs(userA));
        assertFalse(map.get("DEFECT_CLOSED").enabled(), "退订持久化");
        assertTrue(map.get("EXECUTOR_ONLINE").enabled(), "default_off 事件显式订阅");
        Map<String, NotificationPrefItem> otherMap = asMap(prefService.listPrefs(userB));
        assertTrue(otherMap.get("DEFECT_CLOSED").enabled(), "用户隔离");
        assertFalse(otherMap.get("EXECUTOR_ONLINE").enabled(), "用户隔离");

        prefService.updatePrefs(userA, List.of(new NotificationPrefToggle("DEFECT_CLOSED", true)));
        map = asMap(prefService.listPrefs(userA));
        assertTrue(map.get("DEFECT_CLOSED").enabled(), "重新订阅恢复");
        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_notification_pref WHERE user_id = ? AND event_type = 'DEFECT_CLOSED'",
                Long.class, userA);
        assertEquals(1L, rows, "upsert 单行（UNIQUE 键复用）");
    }


    @Test
    void mixedBatchWithForcedDisabledRejectedAtomically() {
        ServiceException ex = assertThrows(ServiceException.class, () -> prefService.updatePrefs(userA, List.of(
                new NotificationPrefToggle("REVIEW_REMIND", false),
                new NotificationPrefToggle("LOGIN_LOCKED", false))));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode(), "白名单 400");
        assertTrue(ex.getMessage().contains("安全通知不可退订"), "400 人话：" + ex.getMessage());

        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_notification_pref WHERE user_id = ?", Long.class, userA);
        assertEquals(0L, rows, "混合批量整笔拒绝，无半生效残留行");
    }

    @Test
    void eachForcedEventRejectedIndividually() {
        for (String forced : List.of("LOGIN_LOCKED", "LOGIN_SESSION_REVOKE", "REVIEW_TIMEOUT")) {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> prefService.updatePrefs(userA, List.of(new NotificationPrefToggle(forced, false))),
                    forced + " 属强制订阅白名单应拒绝");
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        }
        prefService.updatePrefs(userA, List.of(new NotificationPrefToggle("LOGIN_LOCKED", true)));
        assertTrue(asMap(prefService.listPrefs(userA)).get("LOGIN_LOCKED").enabled());
    }

    @Test
    void unknownEventTypeAndDuplicateRejected() {
        ServiceException unknown = assertThrows(ServiceException.class,
                () -> prefService.updatePrefs(userA, List.of(new NotificationPrefToggle("NOT_A_REAL_EVENT", false))));
        assertEquals(BizErrorCode.VALIDATION_ERROR, unknown.getCode());
        assertTrue(unknown.getMessage().contains("未知"), "未知事件 400 人话：" + unknown.getMessage());

        ServiceException dup = assertThrows(ServiceException.class,
                () -> prefService.updatePrefs(userA, List.of(
                        new NotificationPrefToggle("DEFECT_CLOSED", false),
                        new NotificationPrefToggle("DEFECT_CLOSED", true))));
        assertEquals(BizErrorCode.VALIDATION_ERROR, dup.getCode());

        assertThrows(ServiceException.class, () -> prefService.updatePrefs(userA, List.of()), "空批量 400");
    }


    @Test
    void unsubscribedUserGetsNoNotificationOthersDo() {
        prefService.updatePrefs(userA, List.of(new NotificationPrefToggle("DEFECT_CLOSED", false)));

        long idA = notificationService.create(new NotificationCreateInput(
                userA, null, "DEFECT_CLOSED", "缺陷已关闭", "内容A", "P5T:closed:1-" + runToken));
        long idB = notificationService.create(new NotificationCreateInput(
                userB, null, "DEFECT_CLOSED", "缺陷已关闭", "内容B", "P5T:closed:2-" + runToken));

        assertEquals(0L, idA, "退订者生成被过滤（0=未创建）");
        assertEquals(0L, notifCount(userA, "DEFECT_CLOSED"), "退订者站内信 0 行");
        assertTrue(idB > 0, "他人仍正常生成");
        assertEquals(1L, notifCount(userB, "DEFECT_CLOSED"));

        prefService.updatePrefs(userA, List.of(new NotificationPrefToggle("DEFECT_CLOSED", true)));
        notificationService.create(new NotificationCreateInput(
                userA, null, "DEFECT_CLOSED", "缺陷已关闭", "内容A2", "P5T:closed:3-" + runToken));
        assertEquals(1L, notifCount(userA, "DEFECT_CLOSED"), "重新订阅恢复接收");
    }

    @Test
    void defaultOffEventNotGeneratedUntilOptIn() {
        notificationService.create(new NotificationCreateInput(
                userA, null, "EXECUTOR_ONLINE", "执行器恢复", "恢复内容", "P5T:online:1-" + runToken));
        assertEquals(0L, notifCount(userA, "EXECUTOR_ONLINE"), "default_off 无记录即不生成");

        prefService.updatePrefs(userA, List.of(new NotificationPrefToggle("EXECUTOR_ONLINE", true)));
        notificationService.create(new NotificationCreateInput(
                userA, null, "EXECUTOR_ONLINE", "执行器恢复", "恢复内容", "P5T:online:2-" + runToken));
        assertEquals(1L, notifCount(userA, "EXECUTOR_ONLINE"), "显式订阅后可生成");
    }

    @Test
    void unknownEventTypeCreatePassesThrough() {
        long id = notificationService.create(new NotificationCreateInput(
                userA, null, "SOME_FUTURE_EVENT", "标题", "内容", null));
        assertTrue(id > 0, "字典外事件放行");
    }


    @Test
    void updatePrefWritesAuditLog() {
        prefService.updatePrefs(userA, List.of(new NotificationPrefToggle("REVIEW_REMIND", false)));
        Long audits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_audit_log WHERE object_type = 'notification' AND action = 'update'"
                        + " AND operator_id = ?", Long.class, userA);
        assertTrue(audits != null && audits >= 1, "偏好变更审计落行（07 文档）");
    }
}
