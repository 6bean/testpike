package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceInput;
import com.sixbean.testpike.core.appauto.domain.service.AppautoDeviceService;
import com.sixbean.testpike.core.cluster.domain.service.ClusterComponentService;
import com.sixbean.testpike.core.executor.api.dto.ExecutorSelfCheckView;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ExecutorSelfCheckTest {

    private static final String MARKER = "t345bsc";

    @Autowired
    private ExecutorService executorService;

    @Autowired
    private AppautoDeviceService appautoDeviceService;

    @Autowired
    private ClusterComponentService clusterComponentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> executorIds = new ArrayList<>();
    private final List<String> serials = new ArrayList<>();

    private String uniq;

    @BeforeEach
    void setUp() {
        uniq = Long.toHexString(System.nanoTime());
    }

    @AfterEach
    void tearDown() {
        for (String serial : serials) {
            jdbcTemplate.update("DELETE FROM tp_appauto_lease_run WHERE device_id IN "
                    + "(SELECT id FROM tp_appauto_device WHERE serial = ?)", serial);
            jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE serial = ?", serial);
        }
        for (Long id : executorIds) {
            jdbcTemplate.update("DELETE FROM tp_cluster_component WHERE node_id = ?",
                    String.valueOf(id));
            jdbcTemplate.update("DELETE FROM tp_executor WHERE id = ?", id);
        }
    }

    private long registerWith(Map<String, Object> capabilities) {
        String name = MARKER + "-" + uniq + "-" + executorIds.size();
        long id = executorService.register(new ExecutorRegisterInput(
                name, null, null, "web", "chromium", 2, "1.0.0", 0, capabilities));
        executorIds.add(id);
        return id;
    }

    
    private Map<String, Object> fullCapabilities() {
        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("adb", true);
        tools.put("appium", false);
        tools.put("maestro", false);
        Map<String, Object> runtimes = new LinkedHashMap<>();
        runtimes.put("java", "21.0.12");
        runtimes.put("python", null);
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("hostOs", "T345bsc" + uniq);
        caps.put("tools", tools);
        caps.put("runtimes", runtimes);
        caps.put("engines", List.of("APPIUM", "MOBILEWRIGHT"));
        caps.put("platforms", List.of("ANDROID"));
        caps.put("deviceKinds", List.of("EMULATOR"));
        return caps;
    }

    private long insertDevice(String tag, String serial, List<String> labels) {
        serials.add(serial);
        return appautoDeviceService.register(null, new AppautoDeviceInput(
                MARKER + "-" + tag, serial, "USB", null, "model-x", "14", "1080x1920",
                440, labels, "ANDROID"));
    }

    @Test
    void 能力面齐全_一屏人话全字段_设备数按自动标签口径_台账支撑态随行() {
        long executorId = registerWith(fullCapabilities());

        String ownSerial = MARKER + "-own-" + uniq;
        String otherHostSerial = MARKER + "-oth-" + uniq;
        String manualSerial = MARKER + "-man-" + uniq;
        insertDevice("own", ownSerial,
                List.of(("t345bsc" + uniq).toLowerCase(), "platform:android", "source:auto"));
        insertDevice("oth", otherHostSerial, List.of("windows", "source:auto"));
        insertDevice("man", manualSerial, List.of("linux"));

        clusterComponentService.upsert(String.valueOf(executorId),
                "APPIUM", "2.0.0", "heartbeat", null, null);
        clusterComponentService.upsert(String.valueOf(executorId),
                "MAESTRO", null, "heartbeat", null, null);

        ExecutorSelfCheckView view = executorService.selfCheck(executorId);

        assertEquals(executorId, view.executorId());
        assertTrue(view.name().startsWith(MARKER), "名称随行：" + view.name());
        assertEquals("1.0.0", view.version());
        assertEquals(1, view.status(), "在线态码=池列表同谱（1在线）");
        assertEquals("在线", view.statusText(), "注册即在线：心跳新鲜人话态");
        assertTrue(view.capabilitiesReported());
        assertEquals("T345bsc" + uniq, view.hostOs());

        assertEquals(Boolean.TRUE, view.tools().get("adb"));
        assertEquals(Boolean.FALSE, view.tools().get("appium"));
        assertTrue(view.missingTools().contains("appium"), "缺件点名：" + view.missingTools());
        assertTrue(view.missingTools().contains("maestro"));
        assertEquals(2, view.missingTools().size(), "在场件禁混入缺件点名：" + view.missingTools());

        assertEquals("21.0.12", view.runtimes().get("java"));
        assertTrue(view.runtimes().containsKey("python"), "未装运行时诚实在场（值 null）");

        assertEquals(List.of("APPIUM", "MOBILEWRIGHT"), view.engines());
        assertEquals(List.of("ANDROID"), view.platforms());
        assertEquals(List.of("EMULATOR"), view.deviceKinds());

        assertEquals(1L, view.deviceCount(), "设备建档数=source:auto × hostOs 小写同源口径");

        assertEquals(2, view.components().size(), "部件台账随行（三色原样投影）");
        assertTrue(view.components().stream()
                .anyMatch(c -> "APPIUM".equals(c.componentFamily())), "族名随行");
    }

    @Test
    void 能力面未上报_诚实缺席空视图_禁谎报禁猜设备数() {
        String name = MARKER + "-bare-" + uniq;
        long executorId = executorService.register(new ExecutorRegisterInput(
                name, null, null, "web", "chromium", 2, "1.0.0", 0));
        executorIds.add(executorId);

        ExecutorSelfCheckView view = executorService.selfCheck(executorId);

        assertEquals("在线", view.statusText());
        assertEquals(false, view.capabilitiesReported(), "旧执行器未上报=诚实缺席非空壳在线");
        assertNull(view.hostOs());
        assertTrue(view.tools().isEmpty());
        assertTrue(view.missingTools().isEmpty(), "未上报≠全部缺件（禁谎报缺件清单）");
        assertTrue(view.runtimes().isEmpty());
        assertTrue(view.engines().isEmpty());
        assertNull(view.deviceCount(), "hostOs 未知=设备数不可判，禁拿全表计数冒充");
        assertTrue(view.components().isEmpty(), "无台账行=空列表禁 null");
    }

    @Test
    void 执行器不存在_人话拒答不误报空壳() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> executorService.selfCheck(999_999_999L));
        assertTrue(e.getMessage().contains("不存在"), "人话须点名不存在：" + e.getMessage());
    }
}
