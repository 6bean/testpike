package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoLeaseRunView;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoLeaseRunFlowTest {

    
    static final String DEVICE_TTL_KEY = "appauto.device-lease-ttl-seconds";

    @Autowired
    private AppautoDeviceService deviceService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long deviceId;

    @BeforeEach
    void setUp() {
        String code = "apf" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "appauto流水测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        cleanAll();
        deviceId = deviceService.register(projectId, new AppautoDeviceInput(
                "流水机A", "FLOW-A-" + projectId, "EMULATOR", null, "Pixel 7", "14",
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
        // Fixture-scoped teardown (ratchet): every delete is keyed by this
        // run's project id — a blanket TRUNCATE would wipe leases owned by
        // concurrent suites/chains on the shared dev database. Lease/run/device
        // all carry project_id and no FK points into tp_resource_lease.
        jdbcTemplate.update("DELETE FROM tp_resource_lease WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_appauto_lease_run WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE project_id = ?", projectId);
    }


    @Test
    void acquire_writesActiveFlowRow_withLeaseExpiryValues() {
        deviceService.acquireLease(deviceId, 8801L, "execA");

        List<AppautoLeaseRunView> rows = deviceService.listLeaseRuns(deviceId);
        assertEquals(1, rows.size(), "占用成功必写 1 行流水");
        AppautoLeaseRunView run = rows.get(0);
        assertEquals("ACTIVE", run.state(), "新占用流水态=ACTIVE");
        assertEquals("execA", run.holderExecutorId());
        assertEquals(Long.valueOf(8801L), run.batchId(), "批次归属透传（排队明细按批次聚合消费）");
        assertEquals(Long.valueOf(projectId), Long.valueOf(run.projectId()), "流水行项目归属=设备档案项目");
        assertNotNull(run.acquiredAt());
        assertNotNull(run.expiresAt());
        long window = Duration.between(run.acquiredAt(), run.expiresAt()).getSeconds();
        assertTrue(window > 3500 && window <= 3600,
                "流水过期窗=租约 device 档现值（实测 " + window + "s，K8 键=3600）");
    }


    @Test
    void release_stampsReleasedWithReason() {
        deviceService.acquireLease(deviceId, null, "execA");
        deviceService.releaseLease(deviceId, "execA", "用例执行完成");

        AppautoLeaseRunView run = deviceService.listLeaseRuns(deviceId).get(0);
        assertEquals("RELEASED", run.state());
        assertNotNull(run.releasedAt(), "归还时刻必落（排队等待时长计算源）");
        assertEquals("用例执行完成", run.releaseReason(), "归因人话原样入库");

        deviceService.releaseLease(deviceId, "execA", "重复点击归还");
        List<AppautoLeaseRunView> after = deviceService.listLeaseRuns(deviceId);
        assertEquals(1, after.size(), "归还幂等禁写出第二行流水");
        assertEquals("用例执行完成", after.get(0).releaseReason(), "已归因行禁被重复归还覆盖");
    }


    @Test
    void sweepExpired_stampsExpiredOnlyForDueRuns() {
        deviceService.acquireLease(deviceId, null, "execA");
        long otherDevice = deviceService.register(projectId, new AppautoDeviceInput(
                "流水机B", "FLOW-B-" + projectId, "EMULATOR", null, "Pixel 7", "14",
                "1080x2400", 420, List.of("pool")));
        deviceService.acquireLease(otherDevice, null, "execB");
        jdbcTemplate.update("UPDATE tp_resource_lease SET expires_at = now() - interval '1 second'"
                + " WHERE kind='device' AND resource_ref='FLOW-A-" + projectId + "'");

        int swept = deviceService.sweepExpiredDeviceLeases();

        // Sweep counts are global by product design; on the shared dev database
        // other sources' due leases make an exact equality a window bet. The
        // "only due leases are reclaimed" contract is pinned on this fixture's
        // own A/B rows below (A→EXPIRED, B stays ACTIVE).
        assertTrue(swept >= 1,
                "到期租约必在本轮回收集内（实测回收 " + swept + " 条）");
        AppautoLeaseRunView a = deviceService.listLeaseRuns(deviceId).get(0);
        assertEquals("EXPIRED", a.state());
        assertNotNull(a.releaseReason(), "超时回收必带归因文案");
        assertTrue(a.releaseReason().contains("超时"), "归因人话含超时语义: " + a.releaseReason());
        AppautoLeaseRunView b = deviceService.listLeaseRuns(otherDevice).get(0);
        assertEquals("ACTIVE", b.state(), "未到期设备流水不误伤");
    }


    @Test
    void acquire_mutexRejected_writesNoFlowRow() {
        deviceService.acquireLease(deviceId, null, "execA");

        assertThrows(ServiceException.class,
                () -> deviceService.acquireLease(deviceId, 99L, "execB"),
                "活跃未过期占用互斥人话拒绝");

        List<AppautoLeaseRunView> rows = deviceService.listLeaseRuns(deviceId);
        assertEquals(1, rows.size(), "抢占失败方禁写流水行（展示面禁脏数据）");
        assertEquals("execA", rows.get(0).holderExecutorId());
    }


    @Test
    void deviceSwitch_eventSequence_descByLatest() {
        deviceService.acquireLease(deviceId, 7L, "execA");
        deviceService.releaseLease(deviceId, "execA", "设备失联换机");
        long otherDevice = deviceService.register(projectId, new AppautoDeviceInput(
                "流水机C", "FLOW-C-" + projectId, "EMULATOR", null, "Pixel 7", "14",
                "1080x2400", 420, List.of("pool")));
        deviceService.acquireLease(otherDevice, 7L, "execA");

        List<AppautoLeaseRunView> switchFrom = deviceService.listLeaseRuns(deviceId);
        assertEquals(1, switchFrom.size());
        assertEquals("设备失联换机", switchFrom.get(0).releaseReason());
        assertEquals("RELEASED", switchFrom.get(0).state());

        deviceService.releaseLease(otherDevice, "execA", "完成");
        deviceService.acquireLease(otherDevice, 8L, "execD");
        List<AppautoLeaseRunView> timeline = deviceService.listLeaseRuns(otherDevice);
        assertEquals(2, timeline.size());
        assertTrue(timeline.get(0).id() > timeline.get(1).id(), "流水按 id 倒序=最新在前");
        assertEquals("ACTIVE", timeline.get(0).state());
        assertEquals("RELEASED", timeline.get(1).state());
        assertEquals("完成", timeline.get(1).releaseReason());
    }


    @Test
    void acquire_unknownDevice_failsFast_noLeaseNoFlow() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> deviceService.acquireLease(999999999L, null, "execA"));
        assertTrue(e.getMessage().contains("设备"), "人话文案: " + e.getMessage());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_appauto_lease_run WHERE device_id = 999999999",
                Integer.class));
    }

}
