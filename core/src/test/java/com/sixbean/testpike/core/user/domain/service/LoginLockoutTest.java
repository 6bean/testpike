package com.sixbean.testpike.core.user.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.user.api.dto.UserCreateInput;
import com.sixbean.testpike.core.user.api.dto.UserInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class LoginLockoutTest {

    @Autowired
    private UserService userService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private LoginLockService loginLockService;

    private static final String PWD = "Passw0rd!a";

    /** Suite username prefix; the LIKE sweep below is the only teardown channel. */
    private static final String PREFIX = "fxlk_";

    @BeforeEach
    void dropStaleFixtureUsers() {
        SharedDbFixtureSweep.usersByPatternDown(jdbcTemplate, PREFIX + "%");
    }

    @AfterEach
    void restore() {
        // Sweep this test's fixture users with their notifications/audit rows,
        // then restore the login-lock config defaults (the threshold-raising
        // test mutates them in place; the in-memory cache re-reads on update).
        SharedDbFixtureSweep.usersByPatternDown(jdbcTemplate, PREFIX + "%");
        configService.update(0L, configId("testpike.login.max-fail"),
                new ConfigInput("testpike.login.max-fail", "5", "安全", "int", 0, "登录连续失败锁定阈值（默认 5 次）"));
        configService.update(0L, configId("testpike.login.lock-minutes"),
                new ConfigInput("testpike.login.lock-minutes", "15", "安全", "int", 0, "登录锁定时长（默认 15 分钟）"));
    }

    private long configId(String key) {
        Long id = jdbcTemplate.query(
                "SELECT id FROM tp_sys_config WHERE config_key = ?", rs -> rs.next() ? rs.getLong(1) : null, key);
        return id == null ? 0L : id;
    }

    private long createUser(String base) {
        String username = PREFIX + base;
        return userService.create(99L, new UserCreateInput(username, PWD, "测试" + base,
                username + "@testpike.local", "13800000000", 1));
    }

 
    private void failTimes(String base, String password, int times) {
        String username = PREFIX + base;
        for (int i = 0; i < times; i++) {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> userService.authenticate(username, password),
                    "第 " + (i + 1) + " 次失败应抛 40101");
            assertEquals(BizErrorCode.UNAUTHORIZED, ex.getCode(), "失败提示统一 40101");
        }
    }

    @Test
    void 连续5次失败_第6次登录_423锁定人话含剩余时间() {
        createUser("locker_a");

        failTimes("locker_a", "WrongPass1!", 5);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> userService.authenticate(PREFIX + "locker_a", PWD));
        assertEquals(BizErrorCode.LOCKED, ex.getCode(), "第 6 次登录 42301");
        assertTrue(ex.getMessage().contains("锁定"), "人话含「锁定」: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("分钟"), "人话含剩余时间: " + ex.getMessage());

        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> userService.authenticate(PREFIX + "locker_a", PWD));
        assertEquals(BizErrorCode.LOCKED, ex2.getCode());
    }

    @Test
    void 管理员解锁后_正常登录() {
        long id = createUser("locker_b");
        failTimes("locker_b", "WrongPass1!", 5);
        assertThrows(ServiceException.class,
                () -> userService.authenticate(PREFIX + "locker_b", PWD));

        UserInfo unlocked = userService.unlock(1L, id);
        assertNull(unlocked.lockedUntil(), "解锁后 locked_until 清空");
        assertEquals(0, unlocked.failCount(), "解锁后 fail_count 清零");

        UserInfo info = userService.authenticate(PREFIX + "locker_b", PWD);
        assertEquals(id, info.id(), "解锁后正确密码正常登录");
    }

    @Test
    void 锁定与解锁行为写入审计() {
        long id = createUser("locker_c");
        failTimes("locker_c", "WrongPass1!", 5);

        List<Map<String, Object>> lockLogs = jdbcTemplate.queryForList(
                "SELECT action, object_type, object_id FROM tp_audit_log WHERE object_type = 'user' AND object_id = ?",
                String.valueOf(id));
        assertTrue(lockLogs.stream().anyMatch(r -> "lock".equals(r.get("action"))),
                "锁定行为进审计（TC-N1-01）: " + lockLogs);

        userService.unlock(1L, id);
        List<Map<String, Object>> logs = jdbcTemplate.queryForList(
                "SELECT action FROM tp_audit_log WHERE object_type = 'user' AND object_id = ?",
                String.valueOf(id));
        assertTrue(logs.stream().anyMatch(r -> "unlock".equals(r.get("action"))),
                "解锁行为进审计: " + logs);
    }

    @Test
    void 锁定过期自动重置_可正常登录() {
        long id = createUser("locker_d");
        failTimes("locker_d", "WrongPass1!", 5);

        jdbcTemplate.update(
                "UPDATE tp_user SET locked_until = now() - interval '1 minute' WHERE id = ?", id);

        UserInfo info = userService.authenticate(PREFIX + "locker_d", PWD);
        assertEquals(id, info.id(), "锁定过期自动重置后可正常登录（IT-N1-01 补充）");
        assertEquals(0, info.failCount(), "成功后 fail_count 清零");
    }


    /**
     * LOGIN_LOCKED rows emitted about a lockee — dedup_key segment 2 names
     * the locked user, so this window covers every recipient without ever
     * touching alerts about other users (replaces the old blanket TRUNCATE
     * isolation).
     */
    private List<Map<String, Object>> lockAlertsAbout(long lockeeId) {
        return jdbcTemplate.queryForList(
                "SELECT user_id, title, content, dedup_key FROM tp_notification"
                        + " WHERE event_type = 'LOGIN_LOCKED'"
                        + " AND split_part(dedup_key, ':', 2) = ?",
                String.valueOf(lockeeId));
    }

    /** Alerts about the lockee narrowed to the recipients this class tracks. */
    private List<Map<String, Object>> alertsTo(long lockeeId, long... trackedRecipients) {
        Set<Long> wanted = Arrays.stream(trackedRecipients).boxed()
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return lockAlertsAbout(lockeeId).stream()
                .filter(r -> wanted.contains(((Number) r.get("user_id")).longValue()))
                .toList();
    }

 /** Ensure a system admin (role=4) exists and return its id (fixture-created if absent). */
    private long ensureAdmin(String base) {
        String username = PREFIX + base;
        Long id = jdbcTemplate.query(
                "SELECT id FROM tp_user WHERE username = ? AND deleted = 0",
                rs -> rs.next() ? rs.getLong(1) : null, username);
        if (id != null) {
            return id;
        }
        return userService.create(99L, new UserCreateInput(username, PWD, "测试管理员" + base,
                username + "@testpike.local", null, 4));
    }

    @Test
    void 达阈值锁定_本人和管理员各收一条含文案三要素() {
        long adminId = ensureAdmin("r4_admin_a");
        long id = createUser("r4_lockee");

        failTimes("r4_lockee", "WrongPass1!", 5);

        // Recipients = lockee ∪ every system admin. The shipped factory admin
        // keeps its own alert row on the shared baseline; the TC-R4-01 dual
        // assertion is scoped to the recipients tracked by this class.
        List<Map<String, Object>> about = lockAlertsAbout(id);
        List<Map<String, Object>> tracked = alertsTo(id, id, adminId);
        assertEquals(2, tracked.size(), "本人+管理员各一条（TC-R4-01 双接收人）: " + tracked);
        assertTrue(tracked.stream().anyMatch(r -> ((Number) r.get("user_id")).longValue() == id),
                "接收人含本人");
        assertTrue(tracked.stream().anyMatch(r -> ((Number) r.get("user_id")).longValue() == adminId),
                "接收人含系统管理员");
        for (Map<String, Object> a : about) {
            String content = (String) a.get("content");
            assertTrue(content.contains("连续 5 次"), "文案含失败次数: " + content);
            assertTrue(content.contains("15 分钟"), "文案含自动解锁时间: " + content);
            assertTrue(content.contains("修改密码"), "文案含改密提示: " + content);
            assertTrue(((String) a.get("dedup_key")).startsWith("LOGIN_LOCKED:"),
                    "去重键前缀: " + a.get("dedup_key"));
        }

        userService.unlock(1L, id);
        assertEquals(2, alertsTo(id, id, adminId).size(), "解锁不补发/不新增（避免噪音）");
    }

    @Test
    void 本人即管理员_去重只发一条() {
        long adminId = ensureAdmin("r4_solo_admin");

        failTimes("r4_solo_admin", "WrongPass999!", 5);

        List<Map<String, Object>> self = alertsTo(adminId, adminId);
        assertEquals(1, self.size(), "本人=管理员只 1 条（收件人集合去重）: " + self);
        assertEquals(adminId, ((Number) self.get(0).get("user_id")).longValue());
    }

    @Test
    void 锁定事务回滚_不发通知不虚假告警() {
        long id = createUser("r4_rollback");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            loginLockService.applyLock(id, PREFIX + "r4_rollback", 5, 15);
            throw new IllegalStateException("模拟业务回滚");
        }));

        assertEquals(0, lockAlertsAbout(id).size(), "回滚不得产生锁定通知（不虚假告警）");
        assertNull(jdbcTemplate.queryForObject(
                "SELECT locked_until FROM tp_user WHERE id = ?", Timestamp.class, id),
                "锁定更新随事务回滚");
    }

    @Test
    void 调大锁定阈值_新失败计数按新阈值即时生效() {
        createUser("locker_e");
        failTimes("locker_e", "WrongPass1!", 4);

        configService.update(0L, configId("testpike.login.max-fail"),
                new ConfigInput("testpike.login.max-fail", "10", "安全", "int", 0, "调大"));
        failTimes("locker_e", "WrongPass1!", 5);
        failTimes("locker_e", "WrongPass1!", 1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> userService.authenticate(PREFIX + "locker_e", PWD));
        assertEquals(BizErrorCode.LOCKED, ex.getCode(), "累计 10 次失败后锁定（新阈值）");
    }
}
