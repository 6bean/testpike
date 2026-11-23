package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.TakeoverCreateInput;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoTakeoverInputQueueTest {

    
    private static final long HOLDER_EXECUTOR_ID = 987_654_321L;

    @Autowired
    private AppautoTakeoverService takeoverService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ConfigCacheHolder configCacheHolder;

    private long projectId;
    private long requesterId;
    private long occupierId;
    private long deviceId;
    private long batchId;
    private long taskId;
    private String queueKey;
    private String savedQueueMax;
    private String savedQueueTtl;

    @BeforeEach
    void setUp() {
        savedQueueMax = configCacheHolder.get(AppautoConfigReader.KEY_TAKEOVER_INPUT_QUEUE_MAX);
        savedQueueTtl = configCacheHolder.get(AppautoConfigReader.KEY_TAKEOVER_INPUT_TTL_SECONDS);
        String code = "tkin" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_user (username, password_hash, display_name, role, status)"
                        + " VALUES (?, 'x', '接管输入申请人', 2, 1)", "tkin-req-" + code);
        requesterId = jdbcTemplate.queryForObject("SELECT id FROM tp_user WHERE username = ?",
                Long.class, "tkin-req-" + code);
        jdbcTemplate.update("INSERT INTO tp_user (username, password_hash, display_name, role, status)"
                        + " VALUES (?, 'x', '接管输入派发人', 3, 1)", "tkin-occ-" + code);
        occupierId = jdbcTemplate.queryForObject("SELECT id FROM tp_user WHERE username = ?",
                Long.class, "tkin-occ-" + code);
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status, created_by) VALUES (?, ?, 1, ?)",
                "接管输入项目-" + code, code, requesterId);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        jdbcTemplate.update("INSERT INTO tp_appauto_device (project_id, name, serial, device_type)"
                        + " VALUES (?, '接管输入测试机', ?, 'ADB')", projectId, "tkin-" + code);
        deviceId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_appauto_device WHERE serial = ?", Long.class, "tkin-" + code);
        jdbcTemplate.update("INSERT INTO tp_exec_batch (project_id, batch_no, name, status, type,"
                        + " created_by) VALUES (?, ?, '接管输入测试批', 2, 1, ?)",
                projectId, "tkin-" + code, occupierId);
        batchId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_exec_batch WHERE batch_no = ?", Long.class, "tkin-" + code);
        jdbcTemplate.update("INSERT INTO tp_exec_task (project_id, case_id, batch_id, status,"
                        + " schema_version, action_json, task_type)"
                        + " VALUES (?, 0, ?, 2, 'appauto-v1', '{\"kind\":\"app-auto-run\",\"steps\":[]}', 6)",
                projectId, batchId);
        taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_exec_task WHERE project_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, projectId).longValue();
        jdbcTemplate.update("INSERT INTO tp_appauto_lease_run (device_id, project_id, batch_id,"
                        + " holder_executor_id, state, acquired_at, expires_at)"
                        + " VALUES (?, ?, ?, ?, 'ACTIVE', now(), now() + interval '1 hour')",
                deviceId, projectId, batchId, String.valueOf(HOLDER_EXECUTOR_ID));
    }

    @AfterEach
    void tearDown() {
        if (queueKey != null) {
            redis.delete(queueKey);
        }
        configCacheHolder.put(AppautoConfigReader.KEY_TAKEOVER_INPUT_QUEUE_MAX,
                savedQueueMax == null ? "50" : savedQueueMax);
        configCacheHolder.put(AppautoConfigReader.KEY_TAKEOVER_INPUT_TTL_SECONDS,
                savedQueueTtl == null ? "60" : savedQueueTtl);
        jdbcTemplate.update("DELETE FROM tp_appauto_takeover_request WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_notification WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_appauto_lease_run WHERE device_id = ?", deviceId);
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE id = ?", batchId);
        jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE id = ?", deviceId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user WHERE id IN (?, ?)", requesterId, occupierId);
    }

    private long request() {
        return takeoverService.request(
                new TakeoverCreateInput(deviceId, taskId, requesterId, "输入通道测试")).id();
    }

    private static Map<String, Object> tapCmd(int x, int y) {
        return Map.of("verb", "tap", "x", x, "y", y);
    }


    @Test
    void v205InputQueueK8KeysSeeded() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT config_key, config_value FROM tp_sys_config
                WHERE config_key IN ('appauto.takeover.input-ttl-seconds',
                                     'appauto.takeover.input-queue-max')
                """);
        assertEquals(2, rows.size(), "V205 两枚 K8 键必须落库（TTL/容量全 K8 可配禁硬编码）");
        for (Map<String, Object> row : rows) {
            assertNotNull(row.get("config_value"));
        }
    }


    @Test
    void enqueueThenPollFifoAndDrainOnce() {
        long id = request();
        takeoverService.agree(id, occupierId);
        queueKey = takeoverService.inputQueueKey(id);
        takeoverService.enqueueInput(id, requesterId, List.of(tapCmd(10, 20), tapCmd(30, 40)));
        List<Map<String, Object>> first =
                takeoverService.pollInputCommands(id, HOLDER_EXECUTOR_ID, 5);
        assertEquals(2, first.size(), "两笔输入按入队序 FIFO 领取");
        assertEquals(10, ((Number) first.get(0).get("x")).intValue());
        assertEquals(30, ((Number) first.get(1).get("x")).intValue());
        assertEquals(List.of(), takeoverService.pollInputCommands(id, HOLDER_EXECUTOR_ID, 5),
                "领取即出队——二次领取空集禁重放");
    }


    @Test
    void pollByForeignExecutorRejected() {
        long id = request();
        takeoverService.agree(id, occupierId);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> takeoverService.pollInputCommands(id, 111_222_333L, 5));
        assertTrue(ex.getMessage().contains("设备当前持有"),
                "非持有执行器领取=人话拒 fail-closed: " + ex.getMessage());
    }


    @Test
    void pendingStateBlocksEnqueueAndPoll() {
        long id = request();
        assertThrows(ServiceException.class,
                () -> takeoverService.enqueueInput(id, requesterId, List.of(tapCmd(1, 2))));
        assertThrows(ServiceException.class,
                () -> takeoverService.pollInputCommands(id, HOLDER_EXECUTOR_ID, 5));
        takeoverService.agree(id, occupierId);
        takeoverService.enqueueInput(id, requesterId, List.of(tapCmd(1, 2)));
        assertEquals(1, takeoverService.pollInputCommands(id, HOLDER_EXECUTOR_ID, 5).size(),
                "同意后通道开");
    }

    @Test
    void forcedStateOpensChannelForHolder() {
        long id = request();
        long adminId = insertUser("tkin-adm");
        takeoverService.force(id, adminId);
        takeoverService.enqueueInput(id, requesterId, List.of(tapCmd(7, 8)));
        assertEquals(1, takeoverService.pollInputCommands(id, HOLDER_EXECUTOR_ID, 5).size(),
                "强接态同样开道");
    }


    @Test
    void enqueueByNonRequesterRejected() {
        long id = request();
        takeoverService.agree(id, occupierId);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> takeoverService.enqueueInput(id, occupierId, List.of(tapCmd(1, 2))));
        assertTrue(ex.getMessage().contains("仅接管申请人"), "越权入队=人话拒: " + ex.getMessage());
    }


    @Test
    void enqueueOverQueueCapRejectedWholeBatch() {
        long id = request();
        takeoverService.agree(id, occupierId);
        queueKey = takeoverService.inputQueueKey(id);
        configCacheHolder.put(AppautoConfigReader.KEY_TAKEOVER_INPUT_QUEUE_MAX, "3");
        takeoverService.enqueueInput(id, requesterId, List.of(tapCmd(1, 1), tapCmd(2, 2), tapCmd(3, 3)));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> takeoverService.enqueueInput(id, requesterId, List.of(tapCmd(4, 4), tapCmd(5, 5))));
        assertTrue(ex.getMessage().contains("排队"), "超容量=人话拒: " + ex.getMessage());
        assertEquals(3, takeoverService.pollInputCommands(id, HOLDER_EXECUTOR_ID, 10).size(),
                "超限批零入账=已入 3 笔原样可领（禁部分入账失真）");
    }


    @Test
    void endTakeoverDrainsResidualInput() {
        long id = request();
        takeoverService.agree(id, occupierId);
        queueKey = takeoverService.inputQueueKey(id);
        takeoverService.enqueueInput(id, requesterId, List.of(tapCmd(9, 9)));
        takeoverService.end(id, requesterId);
        assertEquals(Boolean.FALSE, redis.hasKey(queueKey),
                "结束接管=输入队列即清（防旧操作在接管外注入）");
    }


    @Test
    void queueKeyTtlRefreshedWithinSeedRange() {
        long id = request();
        takeoverService.agree(id, occupierId);
        queueKey = takeoverService.inputQueueKey(id);
        takeoverService.enqueueInput(id, requesterId, List.of(tapCmd(1, 1)));
        Long ttl = redis.getExpire(queueKey, TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 600, "TTL 随写入刷新且落 seed 域内（当前 " + ttl + "s）");
    }

    private long insertUser(String username) {
        jdbcTemplate.update("INSERT INTO tp_user (username, password_hash, display_name, role, status)"
                        + " VALUES (?, 'x', '接管输入管理员', 1, 1)", username + System.nanoTime());
        return jdbcTemplate.queryForObject(
                "SELECT max(id) FROM tp_user WHERE username LIKE ?", Long.class, username + "%");
    }
}
