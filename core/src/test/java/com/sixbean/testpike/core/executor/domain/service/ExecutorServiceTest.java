package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseRegisterInput;
import com.sixbean.testpike.core.cluster.domain.service.ReleaseManifestService;
import com.sixbean.testpike.core.executor.api.dto.ExecutorInfo;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.notification.domain.component.NotificationEventDict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ExecutorServiceTest {

    @Autowired
    private ExecutorService executorService;

    @Autowired
    private ReleaseManifestService manifestService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Fixture-scoped teardown : drop only this suite's executor
        // naming families (ex-compat-*/ex-0*/ex-a/ex-b) and any task rows pinned to
        // them, the suite's own version-9.9.0 manifest descriptor and the
        // VERSION_UNSUPPORTED alerts deduped under those names. Blanket TRUNCATE of
        // tp_exec_task/tp_executor or a whole-table manifest delete would wipe rows
        // owned by concurrent suites on the shared dev database.
        String family = "name LIKE 'ex-compat-%' OR name LIKE 'ex-0%' OR name IN ('ex-a','ex-b')";
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE executor_id IN ("
                + "SELECT id FROM tp_executor WHERE " + family + ")");
        jdbcTemplate.update("DELETE FROM tp_executor WHERE " + family);
        jdbcTemplate.update("DELETE FROM tp_release_manifest WHERE version = '9.9.0'");
        jdbcTemplate.update("DELETE FROM tp_notification WHERE event_type = 'VERSION_UNSUPPORTED'"
                + " AND dedup_key LIKE 'VERSION_UNSUPPORTED:ex-%'");
    }

    
    private void manifestRequiring(String min, String max) {
        manifestService.register(new ReleaseRegisterInput("9.9.0",
                "[]", "[]", min, max, null, "manual", null, null));
    }

    @Test
    void register_协议版本不兼容_人话拒入池并告警管理员() {
        manifestRequiring("1.3.0", "1.5.0");

        ServiceException e = assertThrows(ServiceException.class,
                () -> executorService.register(new ExecutorRegisterInput(
                        "ex-compat-1", null, null, "web", "chromium", 2, "1.2.0", 0)));
        assertTrue(e.getMessage().contains("不兼容"), "人话须点名协议不兼容：" + e.getMessage());
        assertTrue(e.getMessage().contains("1.3"), "人话须含所需区间：" + e.getMessage());
        assertTrue(e.getMessage().contains("升级"), "人话须指路升级：" + e.getMessage());
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_executor WHERE name = 'ex-compat-1'", Integer.class);
        assertEquals(0, rows, "拒入池=禁留半截行（资安红线：协议不兼容不得在池）");

        Integer alerts = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'VERSION_UNSUPPORTED'",
                Integer.class);
        assertTrue(alerts >= 1, "不兼容拒入池须 VERSION_UNSUPPORTED 告警系统管理员");
    }

    @Test
    void register_同名旧行版本不兼容_更新链同样拒且旧行保留() {
        long old = executorService.register(new ExecutorRegisterInput(
                "ex-compat-2", null, null, "web", "chromium", 2, "1.4.0", 0));
        manifestRequiring("1.3.0", "1.5.0");

        assertThrows(ServiceException.class,
                () -> executorService.register(new ExecutorRegisterInput(
                        "ex-compat-2", null, null, "web", "chromium", 4, "1.2.0", 0)));
        assertEquals("1.4.0", executorService.get(old).version(), "被拒的注册禁把在池行版本改成不兼容值");
    }

    @Test
    void register_兼容区间内_正常入池() {
        manifestRequiring("1.3.0", "1.5.0");

        long id = executorService.register(new ExecutorRegisterInput(
                "ex-compat-3", null, null, "web", "chromium", 2, "1.4.0", 0));
        assertTrue(id > 0);
    }

    @Test
    void register_manifest空表_闸全放行不炸() {
        long id = executorService.register(new ExecutorRegisterInput(
                "ex-compat-4", null, null, "web", "chromium", 2, "0.0.1", 0));
        assertTrue(id > 0);
    }

    @Test
    void register_版本空白_旧执行器零漂移放行() {
        manifestRequiring("1.3.0", "1.5.0");

        long id = executorService.register(new ExecutorRegisterInput(
                "ex-compat-5", null, null, "web", "chromium", 2, null, 0));
        assertTrue(id > 0, "version 空白=旧执行器未上报，零漂移放行（缺失不谎判）");
    }

    @Test
    void heartbeat闸_不兼容行心跳照打但派发消费面过滤_告警不刷屏() {
        manifestRequiring("1.3.0", "1.5.0");
        jdbcTemplate.update("INSERT INTO tp_executor (name, capacity, running_cnt, status, version,"
                + " last_heartbeat) VALUES ('ex-compat-6', 2, 0, 1, '1.0.0', now())");
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_executor WHERE name = 'ex-compat-6'", Long.class);

        executorService.heartbeat(id, 0);
        ExecutorInfo after = executorService.get(id);
        assertEquals(1, after.status(), "心跳仍正常续约（不兼容≠离线态混淆；禁派发由判定现算）");

        assertFalse(executorService.isOnline(id), "不兼容执行器 isOnline 必须 fail-closed（禁派发）");
        assertTrue(executorService.listOnline().stream().noneMatch(e -> e.id() == id),
                "listOnline 派发消费面必须过滤不兼容行");
        assertFalse(executorService.hasOnlineExecutor(), "唯一在线台不兼容=等效无可用执行器（禁谎报有台）");

        Integer alerts = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'VERSION_UNSUPPORTED'"
                        + " AND dedup_key LIKE 'VERSION_UNSUPPORTED:ex-compat-6:%'", Integer.class);
        assertTrue(alerts >= 1, "heartbeat 闸须触发 VERSION_UNSUPPORTED 标红告警（管理员）");

        executorService.heartbeat(id, 0);
        Integer alertsAfter2nd = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'VERSION_UNSUPPORTED'"
                        + " AND dedup_key LIKE 'VERSION_UNSUPPORTED:ex-compat-6:%'", Integer.class);
        assertEquals(alerts, alertsAfter2nd, "同日窗重报 dedup 合并禁刷屏");

        jdbcTemplate.update("UPDATE tp_executor SET version = '1.4.0' WHERE id = ?", id);
        assertTrue(executorService.isOnline(id), "版本升到区间内=派发恢复");
    }

    @Test
    void version_unsupported事件_字典在册且强制订阅() {
        assertTrue(NotificationEventDict.isKnown("VERSION_UNSUPPORTED"),
                "VERSION_UNSUPPORTED 必须入字典（偏好矩阵/卡片白名单单源一致）");
        assertTrue(NotificationEventDict.isForced("VERSION_UNSUPPORTED"),
                "协议不兼容禁派发=资安红线类，强制订阅不可退订");
    }

    @Test
    void register_首次注册_分配id且在线() {
        long id = executorService.register(new ExecutorRegisterInput(
                "ex-001", "1,2", "10,11", "web", "chromium", 2, "1.2.0", 0));

        assertTrue(id > 0);
        ExecutorInfo info = executorService.get(id);
        assertNotNull(info);
        assertEquals("ex-001", info.name());
        assertEquals("1,2", info.groupIds());
        assertEquals(2, info.capacity());
        assertEquals(1, info.status());
        assertEquals(0, info.runningCnt());
        assertNotNull(info.lastHeartbeat());
    }

    @Test
    void register_同名重复注册_保持同一id且更新容量() {
        long first = executorService.register(new ExecutorRegisterInput(
                "ex-001", "1", "10", "web", "chromium", 2, "1.2.0", 0));

        long second = executorService.register(new ExecutorRegisterInput(
                "ex-001", "1", "10", "web", "chromium", 4, "1.3.0", 1));

        assertEquals(first, second, "同名注册必须保持同一 id（重启恢复）");
        ExecutorInfo info = executorService.get(second);
        assertEquals(4, info.capacity());
        assertEquals("1.3.0", info.version());
    }

    @Test
    void heartbeat_刷新心跳与在跑数() {
        long id = executorService.register(new ExecutorRegisterInput(
                "ex-002", null, null, "web", "chromium", 2, "1.0.0", 0));

        executorService.heartbeat(id, 1);

        ExecutorInfo info = executorService.get(id);
        assertEquals(1, info.runningCnt());
        assertEquals(1, info.status());
        assertNotNull(info.lastHeartbeat());
    }

    @Test
    void isOnline_未注册返回false() {
        assertFalse(executorService.isOnline(99999L));
    }

    @Test
    void isOnline_刚注册在线() {
        long id = executorService.register(new ExecutorRegisterInput(
                "ex-003", null, null, "web", "chromium", 2, "1.0.0", 0));

        assertTrue(executorService.isOnline(id));
    }

    @Test
    void listAll_按id倒序返回() {
        long a = executorService.register(new ExecutorRegisterInput(
                "ex-a", null, null, "web", "chromium", 1, "1.0.0", 0));
        long b = executorService.register(new ExecutorRegisterInput(
                "ex-b", null, null, "web", "chromium", 2, "1.0.0", 0));

        List<ExecutorInfo> all = executorService.listAll();
        // Shared dev db: other suites' families may coexist, so pin the contract on
        // global id-desc ordering plus membership of this run's pair (>= the fixture).
        assertTrue(all.size() >= 2, "the two fixture executors must be listed");
        for (int i = 1; i < all.size(); i++) {
            assertTrue(all.get(i - 1).id() >= all.get(i).id(), "listAll 必须按 id 倒序返回");
        }
        assertEquals(2, all.stream().filter(e -> e.id() == a || e.id() == b).count());
    }

    @Test
    void releaseCapacity_执行结束释放容量() {
        long id = executorService.register(new ExecutorRegisterInput(
                "ex-004", null, null, "web", "chromium", 2, "1.0.0", 1));

        executorService.releaseCapacity(id);

        assertEquals(0, executorService.get(id).runningCnt());
    }
}
