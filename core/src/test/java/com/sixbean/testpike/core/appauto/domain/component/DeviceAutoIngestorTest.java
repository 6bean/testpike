package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class DeviceAutoIngestorTest {

    private static final String SERIAL_MARKER = "t345ac";

    @Autowired
    private DeviceAutoIngestor ingestor;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private String projectCode;
    private final List<String> serials = new ArrayList<>();

    @BeforeEach
    void setUp() {
        projectCode = "t345acp" + Long.toHexString(System.nanoTime());
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "设备自动建档测试项目-" + projectCode, projectCode);
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project WHERE code = ?", Long.class, projectCode);
    }

    @AfterEach
    void tearDown() {
        for (String serial : serials) {
            jdbcTemplate.update("DELETE FROM tp_appauto_lease_run WHERE device_id IN "
                    + "(SELECT id FROM tp_appauto_device WHERE serial = ?)", serial);
            jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE serial = ?", serial);
        }
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

 
    private String freshSerial(String tag) {
        String serial = SERIAL_MARKER + "-" + tag + "-" + Long.toHexString(System.nanoTime());
        serials.add(serial);
        return serial;
    }

    
    private Map<String, Object> usbFact(String serial) {
        Map<String, Object> fact = new HashMap<>();
        fact.put("serial", serial);
        fact.put("state", "device");
        fact.put("deviceType", "USB");
        fact.put("platform", "ANDROID");
        fact.put("hostOs", "Linux");
        fact.put("creatable", true);
        fact.put("model", "Pixel7-" + SERIAL_MARKER);
        return fact;
    }

    private Map<String, Object> profileOf(String serial) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT project_id, name, device_type, adb_address, model,"
                        + " health_state, last_seen_at,"
                        + " labels::text AS labels_text, platform"
                        + " FROM tp_appauto_device WHERE serial = ?", serial);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Test
    void creatableRow_filesSharedProfile_withAutoLabels() {
        String serial = freshSerial("usb");
        ingestor.ingest(List.of(usbFact(serial)), null);
        Map<String, Object> row = profileOf(serial);
        assertNotNull(row, "creatable=true 行必须自动建档");
        assertNull(row.get("project_id"), "无作用域=入跨项目共享位（project_id NULL）");
        assertEquals("Pixel7-" + SERIAL_MARKER, row.get("name"), "name 优先取 model");
        assertEquals("USB", row.get("device_type"));
        assertEquals("ANDROID", row.get("platform"));
        String labels = (String) row.get("labels_text");
        assertTrue(labels.contains("linux"), "组标签一=主机OS 小写: " + labels);
        assertTrue(labels.contains("platform:ANDROID"), "组标签二=平台: " + labels);
    }

    @Test
    void missingModel_fallsBackToSerialAsName() {
        String serial = freshSerial("nomodel");
        Map<String, Object> fact = usbFact(serial);
        fact.remove("model");
        ingestor.ingest(List.of(fact), null);
        Map<String, Object> row = profileOf(serial);
        assertNotNull(row);
        assertEquals(serial, row.get("name"), "model 缺位 name 回退 serial（建档单点 name 必填闸不谎报）");
    }

    @Test
    void numericScopeWithExistingProject_filesUnderIt() {
        String serial = freshSerial("scoped");
        ingestor.ingest(List.of(usbFact(serial)), String.valueOf(projectId));
        Map<String, Object> row = profileOf(serial);
        assertNotNull(row);
        assertEquals(projectId, ((Number) row.get("project_id")).longValue(),
                "数字作用域且项目存在=该归属项目建档");
    }

    @Test
    void invalidOrUnknownScope_fallsBackToShared() {
        String wordScope = freshSerial("s1");
        ingestor.ingest(List.of(usbFact(wordScope)), "demo-project");
        assertNull(profileOf(wordScope).get("project_id"), "非数字作用域回退共享位");
        String ghostScope = freshSerial("s2");
        ingestor.ingest(List.of(usbFact(ghostScope)), "999999999");
        assertNull(profileOf(ghostScope).get("project_id"), "项目不存在回退共享位（禁猜归属）");
        String negativeScope = freshSerial("s3");
        ingestor.ingest(List.of(usbFact(negativeScope)), "-1");
        assertNull(profileOf(negativeScope).get("project_id"), "负数作用域回退共享位");
    }

    @Test
    void notCreatableRow_honestSkip() {
        String offline = freshSerial("offline");
        Map<String, Object> fact = usbFact(offline);
        fact.put("state", "offline");
        fact.put("creatable", false);
        ingestor.ingest(List.of(fact), null);
        assertNull(profileOf(offline), "creatable=false（offline/unauthorized）诚实跳过不建档");
        String noFlag = freshSerial("noflag");
        Map<String, Object> fact2 = usbFact(noFlag);
        fact2.remove("creatable");
        ingestor.ingest(List.of(fact2), null);
        assertNull(profileOf(noFlag), "creatable 键缺位=不猜可建档（缺位视同不可建）");
    }

    @Test
    void existingSerial_neverOverwritten() {
        String serial = freshSerial("keep");
        jdbcTemplate.update("INSERT INTO tp_appauto_device (project_id, name, serial, device_type, labels)"
                        + " VALUES (?, '人工编辑名', ?, 'USB', ?::jsonb)",
                projectId, serial, "[\"manual\"]");
        ingestor.ingest(List.of(usbFact(serial)), null);
        Map<String, Object> row = profileOf(serial);
        assertEquals("人工编辑名", row.get("name"), "同 serial=幂等回读既有行，人工编辑字段禁覆盖");
        assertTrue(((String) row.get("labels_text")).contains("manual"),
                "既有标签不动（自动打标只随新建行并入，禁覆盖人工标签）");
    }

    @Test
    void adbRowWithHostPort_filesWithAddress() {
        String serial = freshSerial("adb");
        Map<String, Object> fact = usbFact(serial);
        fact.put("deviceType", "ADB");
        fact.put("adbAddress", "192.168.7.9:5555");
        ingestor.ingest(List.of(fact), null);
        Map<String, Object> row = profileOf(serial);
        assertNotNull(row);
        assertEquals("ADB", row.get("device_type"));
        assertEquals("192.168.7.9:5555", row.get("adb_address"), "远程 ADB 接入地址=探活寻址源入账");
    }

    @Test
    void badRowsSkippedPerRow_goodRowStillFiled() {
        String good = freshSerial("good");
        String badAdb = freshSerial("badadb");
        Map<String, Object> adbNoAddress = usbFact(badAdb);
        adbNoAddress.put("deviceType", "ADB");
        adbNoAddress.remove("adbAddress");
        Map<String, Object> blankSerial = usbFact("  ");
        assertDoesNotThrow(() -> ingestor.ingest(
                List.of("junk-not-a-map", blankSerial, adbNoAddress, usbFact(good)), null),
                "坏行逐行诚实跳过不得炸心跳消费链");
        assertNull(profileOf(badAdb), "ADB 缺接入地址=建档单点校验闸拒该行（复用 register 禁二套校验）");
        assertNotNull(profileOf(good), "同批坏行不影响好行建档");
    }

    @Test
    void emptyOrNullList_zeroAction() {
        assertDoesNotThrow(() -> {
            ingestor.ingest(null, null);
            ingestor.ingest(List.of(), null);
        }, "空清单/缺键=零动作（旧执行器零漂移）");
    }

    // Heartbeat online facts feed the device health ledger through the single
    // probe-result source (recordProbeResult): GREEN advance + last_seen_at for
    // reported devices, and a QUARANTINED profile is never washed clean.
    @Test
    void onlineFact_advancesHealthAndLastSeen_viaProbeSingleSource() {
        String serial = freshSerial("probe");
        jdbcTemplate.update("INSERT INTO tp_appauto_device (project_id, name, serial, device_type, labels)"
                        + " VALUES (?, 'health-probe-name', ?, 'EMULATOR', ?::jsonb)",
                projectId, serial, "[\"manual\"]");
        ingestor.ingest(List.of(usbFact(serial)), null);
        Map<String, Object> row = profileOf(serial);
        assertEquals("GREEN", row.get("health_state"),
                "心跳在线事实必须经 recordProbeResult 单源把健康态推进到 GREEN");
        assertNotNull(row.get("last_seen_at"), "心跳在线事实必须更新 last_seen_at");
        assertEquals("health-probe-name", row.get("name"), "健康推进禁动档案编辑字段");
    }

    @Test
    void quarantinedProfile_untouchedByOnlineFact_failClosed() {
        String serial = freshSerial("quar");
        jdbcTemplate.update("INSERT INTO tp_appauto_device (project_id, name, serial, device_type,"
                        + " labels, health_state, consecutive_failures)"
                        + " VALUES (?, 'quar-name', ?, 'EMULATOR', ?::jsonb, 'QUARANTINED', 8)",
                projectId, serial, "[\"manual\"]");
        ingestor.ingest(List.of(usbFact(serial)), null);
        Map<String, Object> row = profileOf(serial);
        assertEquals("QUARANTINED", row.get("health_state"),
                "隔离=人工决策面 fail-closed，在线事实禁自动解禁");
    }
}
