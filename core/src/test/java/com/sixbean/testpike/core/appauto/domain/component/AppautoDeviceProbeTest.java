package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceInput;
import com.sixbean.testpike.core.appauto.domain.service.AppautoDeviceService;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoDeviceProbeTest {

    static final String QUARANTINE_KEY = "appauto.device-quarantine-failures";
    static final String PROBE_TIMEOUT_KEY = "appauto.device-probe-timeout-ms";

    @Autowired
    private AppautoDeviceService deviceService;

    @Autowired
    private RemoteAdbConnectivityProbe connectivityProbe;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private ServerSocket liveSocket;
    private int livePort;
    private int deadPort;

    @BeforeEach
    void setUp() throws IOException {
        String code = "apxp" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "appauto探活测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        cleanDevices();
        liveSocket = new ServerSocket(0);
        livePort = liveSocket.getLocalPort();
        try (ServerSocket tmp = new ServerSocket(0)) {
            deadPort = tmp.getLocalPort();
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        liveSocket.close();
        cleanDevices();
        configCacheHolder.remove(QUARANTINE_KEY);
        configCacheHolder.remove(PROBE_TIMEOUT_KEY);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private void cleanDevices() {
        if (projectId != 0) {
            jdbcTemplate.update("DELETE FROM tp_appauto_lease_run WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM tp_resource_lease WHERE kind = 'device'"
                    + " AND project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE project_id = ?", projectId);
        }
    }

    private AppautoDeviceInput adbInput(String name, String serial, String adbAddress) {
        return new AppautoDeviceInput(name, serial, "ADB", adbAddress, "Pixel 7", "14",
                "1080x2400", 420, List.of("probe"), "ANDROID");
    }

    private long registerAdb(String serial, String adbAddress) {
        return deviceService.register(projectId, adbInput("探活机-" + serial, serial, adbAddress));
    }

    private String healthOf(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT health_state FROM tp_appauto_device WHERE id = ?", String.class, id);
    }

    private int failuresOf(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT consecutive_failures FROM tp_appauto_device WHERE id = ?", Integer.class, id);
    }


    @Test
    void adbAddressWithoutPortRejected() {
        ServiceException err = assertThrows(ServiceException.class, () -> registerAdb(
                "PROBE-BAD-" + System.nanoTime(), "192.168.1.50"));
        assertTrue(err.getMessage().contains("主机") && err.getMessage().contains("端口"),
                "缺端口=人话点名主机:端口形态: " + err.getMessage());
    }

    @Test
    void adbAddressPortOutOfRangeRejected() {
        ServiceException err = assertThrows(ServiceException.class, () -> registerAdb(
                "PROBE-BAD2-" + System.nanoTime(), "192.168.1.50:99999"));
        assertTrue(err.getMessage().contains("主机") || err.getMessage().contains("端口"),
                "端口越界同闸人话: " + err.getMessage());
    }

    @Test
    void adbAddressHostPortAccepted() {
        long id = registerAdb("PROBE-OK-" + System.nanoTime(), "10.0.0.8:5555");
        assertTrue(id > 0);
    }


    @Test
    void probeSuccessTurnsGreenAndStampsLastSeen() {
        long id = registerAdb("PROBE-LIVE-" + System.nanoTime(), "127.0.0.1:" + livePort);
        int probed = deviceService.probeRemoteDevices();
        assertTrue(probed >= 1);
        assertEquals("GREEN", healthOf(id));
        assertEquals(0, failuresOf(id));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT last_seen_at FROM tp_appauto_device WHERE id = ?",
                java.sql.Timestamp.class, id), "探活成功必回写 last_seen_at（健康闸在线判据源）");
    }


    @Test
    void probeFailuresQuarantineAtThreshold() {
        long id = registerAdb("PROBE-DEAD-" + System.nanoTime(), "127.0.0.1:" + deadPort);
        deviceService.probeRemoteDevices();
        assertEquals("YELLOW", healthOf(id), "失联 1 次=YELLOW");
        deviceService.probeRemoteDevices();
        assertEquals("RED", healthOf(id), "失联 2 次=RED");
        deviceService.probeRemoteDevices();
        assertEquals("QUARANTINED", healthOf(id),
                "连续失联达 V144 默认阈值 3=自动隔离（复用既有判定链禁二套）");
    }

    @Test
    void quarantineThresholdHotUpdateApplies() {
        configCacheHolder.put(QUARANTINE_KEY, "2");
        long id = registerAdb("PROBE-DEAD2-" + System.nanoTime(), "127.0.0.1:" + deadPort);
        deviceService.probeRemoteDevices();
        deviceService.probeRemoteDevices();
        assertEquals("QUARANTINED", healthOf(id), "阈值热更=2 即生效（第 2 失联即隔离）");
    }


    @Test
    void quarantinedDeviceSurvivesProbeSuccess() {
        long id = registerAdb("PROBE-QUAR-" + System.nanoTime(), "127.0.0.1:" + livePort);
        jdbcTemplate.update("UPDATE tp_appauto_device SET health_state = 'QUARANTINED'"
                + " WHERE id = ?", id);
        deviceService.probeRemoteDevices();
        assertEquals("QUARANTINED", healthOf(id),
                "隔离=人工决策面：探活恢复不自动解禁（fail-closed 洗白防线）");
    }


    @Test
    void nonAdbRowsUntouched() {
        long usbId = deviceService.register(projectId, new AppautoDeviceInput(
                "USB机", "PROBE-USB-" + System.nanoTime(), "USB", null, "Pixel 8", "14",
                "1080x2400", 420, List.of(), "ANDROID"));
        deviceService.probeRemoteDevices();
        assertEquals("UNKNOWN", healthOf(usbId),
                "USB 档案不入探活面（在线权威在执行器心跳），健康态零漂移");
    }


    @Test
    void unresolvableHostCountsAsFailure() {
        long id = registerAdb("PROBE-GHOST-" + System.nanoTime(),
                "ghost-host-" + System.nanoTime() + ".invalid:5555");
        deviceService.probeRemoteDevices();
        assertEquals(1, failuresOf(id), "域名解析失败=诚实失联递增（读不到≠在线）");
        assertEquals("YELLOW", healthOf(id));
    }

    @Test
    void directProbeReturnsFalseOnRefusedTrueOnListening() {
        assertTrue(connectivityProbe.reachable("127.0.0.1:" + livePort, 2000));
        assertTrue(!connectivityProbe.reachable("127.0.0.1:" + deadPort, 2000));
        assertTrue(!connectivityProbe.reachable("bad-format-no-port", 2000),
                "形态外地址=诚实不可达（探活面兜底，建档闸之外脏存量禁炸扫描）");
    }
}
