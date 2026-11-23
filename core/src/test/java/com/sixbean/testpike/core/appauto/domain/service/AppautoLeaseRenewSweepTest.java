package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoLeaseRunView;
import com.sixbean.testpike.core.appauto.domain.component.AppautoLeaseRunRecorder;
import com.sixbean.testpike.core.appauto.domain.component.AppautoLeaseSweepScheduler;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=true")
class AppautoLeaseRenewSweepTest {

    
    static final String DEVICE_TTL_KEY = "appauto.device-lease-ttl-seconds";

    @Autowired
    private AppautoDeviceService deviceService;

    @Autowired
    private AppautoLeaseRunRecorder leaseRunRecorder;

    @Autowired
    private AppautoLeaseSweepScheduler leaseSweepScheduler;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long deviceId;
    private String serial;

    @BeforeEach
    void setUp() {
        String code = "arw" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "租约续租测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        cleanAll();
        serial = "RENEW-A-" + projectId;
        deviceId = deviceService.register(projectId, new AppautoDeviceInput(
                "续租机A", serial, "EMULATOR", null, "Pixel 7", "14",
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

    private LocalDateTime leaseExpires() {
        return jdbcTemplate.queryForObject(
                "SELECT expires_at FROM tp_resource_lease WHERE kind='device' AND resource_ref=?",
                LocalDateTime.class, serial);
    }


    @Test
    void renew_byHolder_extendsLeaseWindow_flowRowStaysUntouched() {
        deviceService.acquireLease(deviceId, null, "execA");
        LocalDateTime before = leaseExpires();
        LocalDateTime flowExpiresBefore = deviceService.listLeaseRuns(deviceId).get(0).expiresAt();

        assertTrue(deviceService.renewLease(deviceId, "execA"), "持有者续租必成功");

        LocalDateTime after = leaseExpires();
        assertTrue(after.isAfter(before),
                "续租=租约行过期窗外推（实测 " + before + " → " + after + "）");
        assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT renew_count FROM tp_resource_lease"
                                + " WHERE kind='device' AND resource_ref=?", Integer.class, serial),
                "续租计数递增（会话存活证据面）");

        List<AppautoLeaseRunView> runs = deviceService.listLeaseRuns(deviceId);
        assertEquals(1, runs.size(), "续租禁写第二行流水（流水=占用会话事件，非心跳计数）");
        assertEquals("ACTIVE", runs.get(0).state(), "续租流水态不动");
        assertEquals(flowExpiresBefore, runs.get(0).expiresAt(),
                "流水行保持占用时刻窗口快照（设计定案=续租只延租约寿命，展示面流水不动）");
    }


    @Test
    void renew_byNonHolder_returnsFalse_leaseWindowUntouched() {
        deviceService.acquireLease(deviceId, null, "execA");
        LocalDateTime before = leaseExpires();

        assertFalse(deviceService.renewLease(deviceId, "execB"),
                "非持有者续租=false（诚实拒绝，禁异常——执行器心跳链禁被单台设备炸停）");
        assertEquals(before, leaseExpires(), "被拒续租不得改动活跃租约窗");
    }

    @Test
    void renew_withoutActiveLease_returnsFalse() {
        deviceService.acquireLease(deviceId, null, "execA");
        deviceService.releaseLease(deviceId, "execA", "执行完成");
        assertFalse(deviceService.renewLease(deviceId, "execA"),
                "已归还后 renew=false（会话已结束的迟到续租诚实拒）");
    }


    @Test
    void renew_unknownDevice_failsFastWithPlainMessage() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> deviceService.renewLease(999999999L, "execA"));
        assertTrue(e.getMessage().contains("设备"), "人话文案: " + e.getMessage());
    }


    @Test
    void deviceIdBySerial_resolvesRegisteredProfile() {
        assertEquals(deviceId, leaseRunRecorder.deviceIdBySerial(serial),
                "档案在位=反查回设备 id（claim 消费口只有 serial）");
        assertNull(leaseRunRecorder.deviceIdBySerial("NOT-REGISTERED-" + projectId),
                "档案缺失=null（调用方按排队跳过处理，禁异常）");
    }


    @Test
    void scheduledSweep_reclaimsDueLeaseWithoutManualTrigger() {
        deviceService.acquireLease(deviceId, null, "execA");
        jdbcTemplate.update("UPDATE tp_resource_lease SET expires_at = now() - interval '1 second'"
                + " WHERE kind='device' AND resource_ref=?", serial);

        int swept = leaseSweepScheduler.sweepExpiredDeviceLeases();

        // Sweep counts are global by product design (the 30s scheduler scans the
        // whole lease table); on the shared dev database other sources' due leases
        // make an exact equality a window bet. The reclaim contract is pinned on
        // this fixture's own lease/run rows below.
        assertTrue(swept >= 1,
                "调度轮必须回收已过期租约（本轮回收数含本用例到期租约，实测 " + swept + "）");
        assertEquals("expired", jdbcTemplate.queryForObject(
                        "SELECT state FROM tp_resource_lease WHERE kind='device' AND resource_ref=?",
                        String.class, serial),
                "租约行=expired（互斥权威单表状态机，禁第二把锁）");
        AppautoLeaseRunView run = deviceService.listLeaseRuns(deviceId).get(0);
        assertEquals("EXPIRED", run.state(), "流水归因=EXPIRED（断线回收事件可观测）");
        assertNotNull(run.releaseReason(), "超时回收归因人话在位");
    }
}
