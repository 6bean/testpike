package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceInput;
import com.sixbean.testpike.core.appauto.domain.service.AppautoDeviceService;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.support.LeaseNamespaceExtension;
import com.sixbean.testpike.core.support.LeaseRefNamespace;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;


@SpringBootTest(classes = CoreApplication.class)
@ExtendWith(LeaseNamespaceExtension.class)
class AppAutoDeviceClaimGateTest {

    private static final String MOBILEWRIGHT_ENGINE =
            "{\"code\":\"MOBILEWRIGHT\",\"platform\":\"ANDROID\",\"deviceKind\":\"EMULATOR\"}";

    private static final String DEVICE_TTL_KEY = "appauto.device-lease-ttl-seconds";

    @Autowired
    private ExecTaskService execTaskService;
    @Autowired
    private ExecutorService executorService;
    @Autowired
    private AppautoDeviceService deviceService;
    @Autowired
    private ConfigCacheHolder configCacheHolder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long deviceId;
    private String serial;
    private final List<Long> executorIds = new ArrayList<>();
    private final List<Long> taskIds = new ArrayList<>();

    @BeforeEach
    void setUp(LeaseRefNamespace ns) {
        cleanAll();
        String code = "agt" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "派发闸测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        // Device serial lives under this run's lease namespace: the lease row claim
        // writes is removed by the extension's targeted segment cleanup, never by a
        // blanket TRUNCATE of the shared table.
        serial = ns.ref("GATE-" + System.nanoTime());
        deviceId = deviceService.register(projectId, new AppautoDeviceInput(
                "闸口机A", serial, "EMULATOR", null, "Pixel 7", "14",
                "1080x2400", 420, List.of("pool")));
        configCacheHolder.put(DEVICE_TTL_KEY, "3600");
    }

    @AfterEach
    void tearDown() {
        cleanAll();
        configCacheHolder.remove(DEVICE_TTL_KEY);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private void cleanAll() {
        // Run-owned teardown only: tasks drop by tracked project id (step snapshots
        // cascade via FK), executors by their registered ids; tp_resource_lease rows
        // are owned by the LeaseRefNamespace segment of the shared extension.
        SharedDbFixtureSweep.byIds(jdbcTemplate, "tp_exec_task", taskIds);
        SharedDbFixtureSweep.byIds(jdbcTemplate, "tp_executor", executorIds);
        taskIds.clear();
        executorIds.clear();
        if (projectId != 0L) {
            jdbcTemplate.update("DELETE FROM tp_appauto_lease_run WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE project_id = ?", projectId);
        }
    }

    private String appEnvelopeWithLease(String leaseRefJson) {
        return "{\"kind\":\"app-auto-run\",\"runId\":1,\"leaseRef\":" + leaseRefJson
                + ",\"engine\":" + MOBILEWRIGHT_ENGINE
                + ",\"steps\":[{\"action\":\"app.launch\",\"target\":{\"pkg\":\"com.demo\"}}]}";
    }

    private long insertTask(String actionJson) {
        long taskId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_task (project_id, case_id, status, schema_version,"
                        + " action_json, task_type) VALUES (?, 0, 1, 'v2', ?, 6) RETURNING id",
                Long.class, projectId, actionJson);
        taskIds.add(taskId);
        return taskId;
    }

    private long registerCapable(String name) {
        // Unique per run: teardown deletes by id, and a leftover row from an aborted
        // run can never collide with a same-name registration on the shared dev DB.
        long executorId = executorService.register(new ExecutorRegisterInput(
                name + "-" + System.nanoTime(), null, null, "web,app", "chromium", 2, "1.0.0", 0,
                Map.of("engines", List.of("MOBILEWRIGHT"), "platforms", List.of("ANDROID"),
                        "deviceKinds", List.of("EMULATOR"))));
        executorIds.add(executorId);
        return executorId;
    }


    @Test
    void claim_skipsAppTask_whenDeviceOccupiedByOtherExecutor() {
        long otherExecutor = registerCapable("gate-ex-other");
        deviceService.acquireLease(deviceId, null, String.valueOf(otherExecutor));
        long exId = registerCapable("gate-ex-blocked");
        long taskId = insertTask(appEnvelopeWithLease(
                "{\"deviceId\":" + deviceId + ",\"serial\":\"" + serial + "\"}"));

        assertNull(execTaskService.claim(exId),
                "设备被异器活跃占用=本器该候选跳过排队（claim 正常返回 null 禁异常）");

        assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT status FROM tp_exec_task WHERE id=?", Integer.class, taskId),
                "被跳过的 APP 任务滞留待派发（排队非失败）");
        assertEquals(0, jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tp_appauto_lease_run WHERE holder_executor_id=?",
                        Integer.class, String.valueOf(exId)),
                "闸前拦截=禁写出占用流水脏行");
    }


    @Test
    void claim_freeDevice_acquiresLeaseAndWritesActiveFlow() {
        long exId = registerCapable("gate-ex-free");
        long taskId = insertTask(appEnvelopeWithLease(
                "{\"deviceId\":" + deviceId + ",\"serial\":\"" + serial + "\"}"));

        ClaimTaskView view = execTaskService.claim(exId);
        assertNotNull(view, "空闲设备的 APP 任务必须可领");
        assertEquals(taskId, view.taskId());

        String holder = jdbcTemplate.queryForObject(
                "SELECT holder_executor_id FROM tp_resource_lease"
                        + " WHERE kind='device' AND resource_ref=? AND state='active'",
                String.class, serial);
        assertEquals(String.valueOf(exId), holder,
                "派发定版=claim 同事务占住设备（互斥权威单表，禁第二把锁）");

        Map<String, Object> run = jdbcTemplate.queryForMap(
                "SELECT state, device_id, batch_id FROM tp_appauto_lease_run"
                        + " WHERE holder_executor_id=?", String.valueOf(exId));
        assertEquals("ACTIVE", run.get("state"), "占用流水随派发写入");
        assertEquals(deviceId, ((Number) run.get("device_id")).longValue(),
                "serial→deviceId 反查单点命中档案行");
    }


    @Test
    void claim_deviceHeldBySameExecutor_renewsExistingSession_noSecondFlowRow() {
        long exId = registerCapable("gate-ex-holder");
        deviceService.acquireLease(deviceId, null, String.valueOf(exId));
        insertTask(appEnvelopeWithLease(
                "{\"deviceId\":" + deviceId + ",\"serial\":\"" + serial + "\"}"));

        ClaimTaskView view = execTaskService.claim(exId);
        assertNotNull(view, "本器持有的设备第二任务必须可领（同会话连续派发天然放行）");

        assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tp_appauto_lease_run WHERE device_id=?",
                        Integer.class, deviceId),
                "续租复用禁写第二行 ACTIVE 流水（流水=占用会话事件非任务计数）");
        assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT renew_count FROM tp_resource_lease"
                                + " WHERE kind='device' AND resource_ref=?", Integer.class, serial),
                "同持有者=延寿续租（renew_count 递增，租约窗外推）");
    }


    @Test
    void claim_legacyStringLeaseRef_notGatedDispatchesNormally() {
        long exId = registerCapable("gate-ex-legacy");
        long taskId = insertTask(appEnvelopeWithLease("\"lease-legacy-1\""));

        ClaimTaskView view = execTaskService.claim(exId);
        assertNotNull(view, "字符串 leaseRef（旧夹具形态）=不判设备闸，派发照常");
        assertEquals(taskId, view.taskId());
        // Pinned to this run's state surface (the device created in setUp): the legacy
        // string leaseRef must not occupy it. A whole-table zero check would read other
        // concurrent suites' leases on the shared dev DB.
        assertEquals(0, jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tp_resource_lease WHERE kind='device' AND resource_ref=?",
                        Integer.class, serial),
                "无 serial 语义=不占租约（形态统一随 executor 侧包收口）");
    }
}
