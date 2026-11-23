package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceInfo;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoDeviceServiceTest {

    static final String QUARANTINE_KEY = "appauto.device-quarantine-failures";

    @Autowired
    private AppautoDeviceService deviceService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void setUp() {
        String code = "apx" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "appauto测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        cleanDevices();
    }

    @AfterEach
    void tearDown() {
        cleanDevices();
        configCacheHolder.remove(QUARANTINE_KEY);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private void cleanDevices() {
        if (projectId != 0) {
            jdbcTemplate.update("DELETE FROM tp_appauto_lease_run WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM tp_resource_lease WHERE kind = 'device'"
                    + " AND project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM tp_appauto_device"
                    + " WHERE project_id IS NULL AND serial LIKE 'SHARETEST-%'");
        }
    }

    private AppautoDeviceInput input(String name, String serial, String type, String adbAddress,
                                     List<String> labels) {
        return new AppautoDeviceInput(name, serial, type, adbAddress, "Pixel 7", "14",
                "1080x2400", 420, labels);
    }

    /**
     * Devices owned by the given project, optionally narrowed by label group.
     *
     * <p>{@code list} also returns system-level shared devices - a project may borrow
     * them, which is the display precondition for borrowing without over-privilege.
     * A live executor runner keeps the local emulator registered as one of those rows,
     * so an assertion over the raw list would measure what happens to be connected
     * during the run instead of the visibility rule under test.
     */
    private List<AppautoDeviceInfo> ownRows(long project, String label) {
        return deviceService.list(project, label).stream()
                .filter(info -> info.projectId() != null && info.projectId() == project)
                .toList();
    }


    @Test
    void register_posAndPdaTypes_acceptedAndReachedByGroupLabel() {
        long posId = deviceService.register(projectId,
                input("收银 POS 一台", "POS9001", "POS", null, List.of("POS 手持组")));
        long pdaId = deviceService.register(projectId,
                input("盘点 PDA 一台", "PDA9001", "PDA", null, List.of("POS 手持组")));

        assertEquals("POS", deviceService.get(posId).deviceType(), "POS 型建档值域必持久");
        assertEquals("PDA", deviceService.get(pdaId).deviceType(), "PDA 型建档值域必持久");
        assertEquals(2, ownRows(projectId, "POS 手持组").size(),
                "标签组圈定=组内两台一次可寻（JSONB 包含查询消费面）");
    }

    @Test
    void register_unknownDeviceType_rejectedListingSupportedFiveStates() {
        com.sixbean.testpike.common.exception.ServiceException ex = org.junit.jupiter.api.Assertions
                .assertThrows(com.sixbean.testpike.common.exception.ServiceException.class,
                        () -> deviceService.register(projectId,
                                input("手表样机", "WCH9001", "WATCH", null, List.of())));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("POS"),
                "拒止文案必须点名五态值域（旧三态文案=误导建档可选面）");
    }


    @Test
    void register_sameSerialSameProject_returnsExistingId_noSecondRow() {
        long first = deviceService.register(projectId,
                input("首发机", "R3CB22AA", "USB", null, List.of("pixel")));
        long again = deviceService.register(projectId,
                input("重报同序列号", "R3CB22AA", "ADB", "192.168.1.9:5555", List.of("pixel")));

        assertEquals(first, again, "serial 项目内冲突回读同一 id（幂等注册）");
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_appauto_device WHERE project_id=? AND serial=?",
                Integer.class, projectId, "R3CB22AA");
        assertEquals(1, rows, "幂等=零第二条档案行（重复上报不复制资产）");
    }

    @Test
    void register_sameSerialDifferentProject_returnsSameGlobalRow_noDoubleBooking() {
        long first = deviceService.register(projectId,
                input("甲项目机", "EMU-001", "EMULATOR", null, List.of()));
        String code = "apx2-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "appauto第二项目-" + code, code);
        long otherProject = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        try {
            long otherId = deviceService.register(otherProject,
                    input("乙项目同名序列号", "EMU-001", "EMULATOR", null, List.of()));
            assertEquals(first, otherId, "跨项目同 serial 幂等回同一全局档案行");
            Integer global = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_appauto_device WHERE serial=?",
                    Integer.class, "EMU-001");
            assertEquals(1, global == null ? 0 : global, "同 serial 全局仅一行（禁双建档）");
        } finally {
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", otherProject);
        }
    }

    @Test
    void list_sharedSlotDevice_visibleToAnyProject() {
        String serial = "SHARETEST-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_appauto_device (project_id, name, serial, device_type)"
                + " VALUES (NULL, '共享机', ?, 'EMULATOR')", serial);
        try {
            List<AppautoDeviceInfo> mine = deviceService.list(projectId, null);
            // Name the row this test inserted: shared devices of any owner are in scope by
            // contract, so an absolute size here would report machine state as a failure.
            List<AppautoDeviceInfo> inserted = mine.stream()
                    .filter(info -> serial.equals(info.serial()))
                    .toList();
            assertEquals(1, inserted.size(), "共享位行对项目列表可见");
            assertNull(inserted.get(0).projectId(), "归属如实回显 null=共享资产");
        } finally {
            jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE serial = ?", serial);
        }
    }


    @Test
    void get_sharedSlotDevice_recordsViewAudit() {
        String serial = "SHARETEST-AUDIT-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_appauto_device (project_id, name, serial, device_type)"
                + " VALUES (NULL, '审计共享机', ?, 'EMULATOR')", serial);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_appauto_device WHERE serial = ?", Long.class, serial);
        try {
            deviceService.get(id);
            List<String> actions = jdbcTemplate.queryForList(
                    "SELECT action FROM tp_audit_log WHERE object_type = 'appauto_device'"
                            + " AND object_id = ? AND action = 'view'", String.class, String.valueOf(id));
            assertEquals(1, actions.size(), "共享位档案被读取必留一条 view 审计（越权追溯数据源）");
            String after = jdbcTemplate.queryForObject(
                    "SELECT after_json::text FROM tp_audit_log WHERE object_type = 'appauto_device'"
                            + " AND object_id = ? AND action = 'view'", String.class, String.valueOf(id));
            assertTrue(after != null && after.contains(serial),
                    "view 审计 after 快照含序列号（追溯读的是哪台机）: " + after);
        } finally {
            jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE serial = ?", serial);
        }
    }

    @Test
    void get_projectOwnedDevice_recordsNoViewAudit() {
        long id = deviceService.register(projectId,
                input("私有机", "AUDIT-OWN-1", "USB", null, List.of()));
        deviceService.get(id);
        Integer views = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'appauto_device'"
                        + " AND object_id = ? AND action = 'view'",
                Integer.class, String.valueOf(id));
        assertEquals(0, views == null ? 0 : views,
                "项目自有档案读取不记 view 审计（全路径刷屏=噪音，审计面只盯共享位）");
    }


    @Test
    void register_illegalInputs_humanReject() {
        ServiceException typeErr = assertThrows(ServiceException.class, () -> deviceService.register(
                projectId, input("怪类型", "S1", "IOS", null, List.of())));
        assertTrue(typeErr.getMessage().contains("EMULATOR"), "类型白名单人话含可选值: " + typeErr.getMessage());

        ServiceException serialErr = assertThrows(ServiceException.class, () -> deviceService.register(
                projectId, input("缺序列号", " ", "USB", null, List.of())));
        assertTrue(serialErr.getMessage().contains("序列号"), "serial 必填人话: " + serialErr.getMessage());

        ServiceException adbErr = assertThrows(ServiceException.class, () -> deviceService.register(
                projectId, input("远程机缺地址", "S2", "ADB", null, List.of())));
        assertTrue(adbErr.getMessage().contains("接入地址"), "ADB 型必带 ip:port 人话: " + adbErr.getMessage());
    }


    @Test
    void probeFailures_escalate_yellowRedQuarantine_thenSuccessKeepsQuarantineUntilManualRestore() {
        long id = deviceService.register(projectId,
                input("失联演练机", "R3CB22BB", "USB", null, List.of()));

        deviceService.recordProbeResult(id, false);
        assertEquals("YELLOW", deviceService.get(id).healthState(), "单次失联=YELLOW 预警");

        deviceService.recordProbeResult(id, false);
        assertEquals("RED", deviceService.get(id).healthState(), "两次失联=RED");

        deviceService.recordProbeResult(id, false);
        assertEquals("QUARANTINED", deviceService.get(id).healthState(), "连续 3 次失联自动隔离（默认阈值 3）");

        deviceService.recordProbeResult(id, true);
        assertEquals("QUARANTINED", deviceService.get(id).healthState(), "隔离态成功心跳不自动解禁");

        deviceService.unquarantine(id);
        AppautoDeviceInfo restored = deviceService.get(id);
        assertEquals("UNKNOWN", restored.healthState(), "人工恢复=回 UNKNOWN 待再探测");
        assertEquals(0, restored.consecutiveFailures(), "人工恢复清零失联计数");

        deviceService.recordProbeResult(id, true);
        assertEquals("GREEN", deviceService.get(id).healthState(), "恢复后再探测成功=GREEN");
    }

    @Test
    void probeQuarantineThreshold_hotConfigurable() {
        configCacheHolder.put(QUARANTINE_KEY, "2");
        long id = deviceService.register(projectId,
                input("阈值热更机", "R3CB22CC", "EMULATOR", null, List.of()));

        deviceService.recordProbeResult(id, false);
        assertEquals("YELLOW", deviceService.get(id).healthState());
        deviceService.recordProbeResult(id, false);
        assertEquals("QUARANTINED", deviceService.get(id).healthState(),
                "阈值改 2 即两次失联隔离（K8 键热更生效，禁硬编码）");
    }

    @Test
    void probeSuccess_resetsFailuresAndGreen() {
        long id = deviceService.register(projectId,
                input("抖动机", "R3CB22DD", "USB", null, List.of()));
        deviceService.recordProbeResult(id, false);
        deviceService.recordProbeResult(id, false);

        deviceService.recordProbeResult(id, true);

        AppautoDeviceInfo info = deviceService.get(id);
        assertEquals("GREEN", info.healthState(), "任一成功复位 GREEN（§5.3 连续失联计数语义）");
        assertEquals(0, info.consecutiveFailures(), "成功清零连续失联计数");
        assertNotNull(info.lastSeenAt(), "成功心跳刷新最后在线时刻");
    }


    @Test
    void overheatNote_transparentThroughGetAndList() {
        long id = deviceService.register(projectId,
                input("高温机", "R3CB22HOT", "USB", null, List.of()));
        jdbcTemplate.update("UPDATE tp_appauto_device SET last_overheat_at = now() WHERE id = ?", id);

        AppautoDeviceInfo info = deviceService.get(id);
        assertNotNull(info.lastOverheatAt(), "过热注记必须透传进设备档案视图（温度徽章数据源单点）");

        List<AppautoDeviceInfo> listed = ownRows(projectId, null);
        assertEquals(1, listed.size(), "the project owns exactly the row this test registered");
        assertNotNull(listed.get(0).lastOverheatAt(), "列表口同样透传（设备墙逐行徽章消费）");
    }

    @Test
    void noOverheat_lastOverheatAtStaysNull() {
        long id = deviceService.register(projectId,
                input("常温机", "R3CB22COOL", "USB", null, List.of()));
        org.junit.jupiter.api.Assertions.assertNull(deviceService.get(id).lastOverheatAt(),
                "未过热设备注记必须为 null（前端徽章据此诚实缺位）");
    }


    @Test
    void list_labelFilter_matchesJsonbArrayContainment() {
        deviceService.register(projectId,
                input("组内一机", "L1A", "EMULATOR", null, List.of("pixel", "快跑组")));
        deviceService.register(projectId,
                input("组内二机", "L1B", "USB", null, List.of("快跑组")));
        deviceService.register(projectId,
                input("组外机", "L1C", "USB", null, List.of("独占组")));

        List<AppautoDeviceInfo> group = ownRows(projectId, "快跑组");
        assertEquals(2, group.size(), "label 组=计划/套件绑组不绑单机（§5.3），命中两机");
        assertEquals(3, ownRows(projectId, null).size(), "无过滤=项目全量");
        assertTrue(deviceService.list(projectId, "不存在组").isEmpty());
    }


    @Test
    void update_serialTakenByOtherDevice_humanConflict() {
        deviceService.register(projectId, input("甲", "U-001", "USB", null, List.of()));
        long second = deviceService.register(projectId, input("乙", "U-002", "USB", null, List.of()));

        ServiceException err = assertThrows(ServiceException.class, () -> deviceService.update(second,
                input("乙改名撞序列号", "U-001", "USB", null, List.of())));
        assertTrue(err.getMessage().contains("序列号"), "撞他人 serial 人话 409: " + err.getMessage());
        assertTrue(err.getMessage().contains("本项目"), "本项目撞号点名本项目归属: " + err.getMessage());

        deviceService.update(second, input("乙改型号", "U-002", "ADB", "10.0.0.8:5555", List.of("改后")));
        AppautoDeviceInfo after = deviceService.get(second);
        assertEquals("乙改型号", after.name());
        assertEquals("ADB", after.deviceType());
        assertEquals("10.0.0.8:5555", after.adbAddress());
    }

    @Test
    void update_serialTakenBySharedDevice_namesSharedOwnership() {
        String serial = "SHARETEST-U-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_appauto_device (project_id, name, serial, device_type)"
                + " VALUES (NULL, '共享撞号机', ?, 'USB')", serial);
        long mine = deviceService.register(projectId, input("我的机", "OWN-" + serial, "USB", null, List.of()));
        try {
            ServiceException err = assertThrows(ServiceException.class, () -> deviceService.update(mine,
                    input("撞共享位", serial, "USB", null, List.of())));
            assertTrue(err.getMessage().contains("共享"), "撞共享位文案点名共享资产: " + err.getMessage());
        } finally {
            jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE serial = ?", serial);
        }
    }

    @Test
    void delete_removesRow_thenGet404Human() {
        long id = deviceService.register(projectId, input("删我", "DEL-1", "USB", null, List.of()));
        deviceService.delete(id);

        ServiceException err = assertThrows(ServiceException.class, () -> deviceService.get(id));
        assertTrue(err.getMessage().contains("不存在"), "删除后详情人话 404: " + err.getMessage());
    }


    @Test
    void delete_inUseRejectedHuman_thenReleaseOk_cleansFlowRows() {
        long id = deviceService.register(projectId, input("在占机", "DEL-2", "USB", null, List.of()));
        deviceService.acquireLease(id, 8802L, "execDel");

        ServiceException err = assertThrows(ServiceException.class, () -> deviceService.delete(id));
        assertTrue(err.getMessage().contains("占用"), "占用中删除人话拒绝（禁裸 FK 500）: " + err.getMessage());

        deviceService.releaseLease(id, "execDel", "退役前归还");
        deviceService.delete(id);
        Integer flows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_appauto_lease_run WHERE device_id = ?", Integer.class, id);
        assertEquals(0, flows == null ? 0 : flows, "档案退役=展示面流水随退役清理（互斥权威行另归租约域）");
        jdbcTemplate.update("DELETE FROM tp_resource_lease WHERE kind = 'device' AND resource_ref = 'DEL-2'");
    }


    private AppautoDeviceInput platformInput(String name, String serial, String platform) {
        return new AppautoDeviceInput(name, serial, "USB", null, "Pixel 7", "14",
                "1080x2400", 420, List.of(), platform);
    }

    @Test
    void register_platform_normalizedUppercase_transparentThroughGetAndList() {
        long id = deviceService.register(projectId,
                platformInput("安卓机", "PLAT-A-" + System.nanoTime(), "android"));
        assertEquals("ANDROID", deviceService.get(id).platform(),
                "大小写归一按平台目录码谱入账（接管注入载体判定源）");
        List<AppautoDeviceInfo> listed = deviceService.list(projectId, null);
        assertEquals("ANDROID", listed.get(0).platform(), "列表口同样透传（前端置灰判据此消费）");
    }

    @Test
    void register_platformBlank_staysNull_honestUndeterminedSlot() {
        long id = deviceService.register(projectId,
                platformInput("未判定机", "PLAT-B-" + System.nanoTime(), "  "));
        assertNull(deviceService.get(id).platform(), "空白=未判定诚实位（禁造默认值冒充平台）");
    }

    @Test
    void register_illegalPlatform_humanReject_namesEnabledOptions() {
        ServiceException err = assertThrows(ServiceException.class, () -> deviceService.register(
                projectId, platformInput("怪平台", "PLAT-C-" + System.nanoTime(), "SYMBIAN")));
        assertTrue(err.getMessage().contains("平台目录启用面"),
                "非法平台码人话拒含可选值（应用档案白名单同源）: " + err.getMessage());
        assertTrue(err.getMessage().contains("ANDROID"), "可选值点名启用面: " + err.getMessage());
    }

    @Test
    void update_platform_changedPersisted() {
        long id = deviceService.register(projectId,
                platformInput("改平台机", "PLAT-D-" + System.nanoTime(), "ANDROID"));
        deviceService.update(id, platformInput("改平台机", "PLAT-D-" + id, "HARMONY_NEXT"));
        assertEquals("HARMONY_NEXT", deviceService.get(id).platform(), "编辑面平台改动落库");
    }

    @Test
    void nineArgCompatConstructor_platformNull_zeroDrift() {
        long id = deviceService.register(projectId,
                input("旧形调用", "PLAT-E-" + System.nanoTime(), "USB", null, List.of()));
        assertNull(deviceService.get(id).platform(), "旧 9 参兼容构造=平台未判定，既有调用方零破坏");
    }
}
