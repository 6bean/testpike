package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.notification.domain.component.NotificationEventDict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class DevicePerfIngestorTest {

    private static final String SERIAL_MARKER = "t329p2c";
    
    private final String serial = SERIAL_MARKER + "-" + Long.toHexString(System.nanoTime());

    @Autowired
    private DevicePerfIngestor ingestor;
    @Autowired
    private AdminAlertRecipients adminAlertRecipients;
    @Autowired
    private ConfigCacheHolder configCacheHolder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private String projectCode;

    @BeforeEach
    void setUp() {
        projectCode = "t9pc" + Long.toHexString(System.nanoTime());
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "性能护栏测试项目-" + projectCode, projectCode);
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project WHERE code = ?", Long.class, projectCode);
        jdbcTemplate.update("INSERT INTO tp_appauto_device (project_id, name, serial, device_type) "
                + "VALUES (?, '过热测试机', ?, 'EMULATOR')", projectId, serial);
        configCacheHolder.put(AppautoConfigReader.KEY_DEVICE_TEMP_MAX, "40");
        configCacheHolder.put(AppautoConfigReader.KEY_DEVICE_HOT_WINDOW_MINUTES, "10");
    }

    @AfterEach
    void tearDown() {
        configCacheHolder.remove(AppautoConfigReader.KEY_DEVICE_TEMP_MAX);
        configCacheHolder.remove(AppautoConfigReader.KEY_DEVICE_HOT_WINDOW_MINUTES);
        configCacheHolder.remove(AppautoConfigReader.KEY_DEVICE_CPU_FULL_PCT);
        jdbcTemplate.update("DELETE FROM tp_notification WHERE event_type = 'DEVICE_OVERHEAT' AND dedup_key LIKE ?",
                "%" + SERIAL_MARKER + "%");
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private String overheatDedupLike() {
        return "%DEVICE_OVERHEAT%" + serial + "%";
    }

    @Test
    void hotSummary_marksDeviceAndAlertsAllAdmins_inReadableChinese() {
        ingestor.ingest(List.of(Map.of(
                "taskId", 999001L, "serial", serial, "pkg", "com.demo",
                "sampleCount", 30, "lastTempC", 41.5, "maxTempC", 43.0,
                "maxCpuPct", 95.0)));

        String overheatAt = jdbcTemplate.queryForObject(
                "SELECT last_overheat_at::text FROM tp_appauto_device WHERE serial = ?",
                String.class, serial);
        assertNotNull(overheatAt, "越阈摘要行必须刷新设备过热注记 last_overheat_at");

        int admins = adminAlertRecipients.adminUserIds().size();
        assertTrue(admins > 0, "dev 库必须存在系统管理员（种子 admin）");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT user_id, title, content, dedup_key FROM tp_notification"
                        + " WHERE event_type = 'DEVICE_OVERHEAT' AND dedup_key LIKE ?",
                overheatDedupLike());
        assertEquals(admins, rows.size(), "越阈告警必须扇出全体系统管理员");
        for (Map<String, Object> r : rows) {
            String content = (String) r.get("content");
            assertTrue(content.contains(serial), "正文必须含设备序列号: " + content);
            assertTrue(content.contains("41.5"), "正文必须含末帧温度现值: " + content);
            assertTrue(content.contains("43"), "正文必须含峰值温度: " + content);
            assertTrue(content.contains("40"), "正文必须含越过的阈值: " + content);
            assertTrue(content.contains("自动跳过"), "正文必须含处置语义人话: " + content);
            assertNotNull(r.get("dedup_key"), "dedup 键必填=同日窗合并单源");
        }
    }

    @Test
    void peakOnlyBreach_alsoMarks() {
        ingestor.ingest(List.of(Map.of(
                "taskId", 999002L, "serial", serial,
                "sampleCount", 10, "lastTempC", 38.0, "maxTempC", 45.5)));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT last_overheat_at::text FROM tp_appauto_device WHERE serial = ?",
                String.class, serial));
    }

    @Test
    void coolSummary_neverMarksNeverAlerts() {
        ingestor.ingest(List.of(Map.of(
                "taskId", 999003L, "serial", serial,
                "sampleCount", 20, "lastTempC", 36.0, "maxTempC", 39.9)));
        String overheatAt = jdbcTemplate.queryForObject(
                "SELECT last_overheat_at::text FROM tp_appauto_device WHERE serial = ?",
                String.class, serial);
        assertNull(overheatAt, "未越阈=注记必须不动（心跳常报禁每拍刷热事件）");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'DEVICE_OVERHEAT' AND dedup_key LIKE ?",
                Integer.class, overheatDedupLike()), "未越阈零告警");
    }

    @Test
    void sameDayWindowRepeat_dedupMergesNoSpam() {
        Map<String, Object> row = Map.of("taskId", 999004L, "serial", serial,
                "sampleCount", 1, "lastTempC", 44.0);
        ingestor.ingest(List.of(row));
        ingestor.ingest(List.of(row));
        int admins = adminAlertRecipients.adminUserIds().size();
        assertEquals(admins, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'DEVICE_OVERHEAT' AND dedup_key LIKE ?",
                Integer.class, overheatDedupLike()), "同日窗重报=dedup 去重单点合并，每人恒一条");
    }

    @Test
    void badRows_skippedHonestly_goodRowsStillAlert() {
        assertDoesNotThrow(() -> ingestor.ingest(List.of(
                "not-a-map",
                Map.of("sampleCount", 5),
                Map.of("serial", "   "),
                Map.of("taskId", 999005L, "serial", serial, "sampleCount", 8,
                        "maxTempC", 42.0))));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT last_overheat_at::text FROM tp_appauto_device WHERE serial = ?",
                String.class, serial), "坏行跳过不得拖累好行消费");
    }

    @Test
    void emptyOrNullRows_zeroAction() {
        assertDoesNotThrow(() -> {
            ingestor.ingest(null);
            ingestor.ingest(List.of());
        });
    }

    @Test
    void unknownSerial_alertsWithoutMarking() {
        ingestor.ingest(List.of(Map.of(
                "taskId", 999006L, "serial", serial + "-ghost",
                "sampleCount", 1, "lastTempC", 50.0)));
        assertEquals(adminAlertRecipients.adminUserIds().size(), jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'DEVICE_OVERHEAT' AND dedup_key LIKE ?",
                Integer.class, "%" + serial + "-ghost%"),
                "无档案 serial=注记 0 行但告警必留痕（执行器上报事实优先，禁静默蒸发）");
    }


    
    private long insertRunningTask() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_task (project_id, case_id, status, task_type, action_json) "
                        + "VALUES (?, 990001, 2, 6, '{}') RETURNING id",
                Long.class, projectId);
    }

    private String errorDetailOf(long taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT error_detail FROM tp_exec_task WHERE id = ?", String.class, taskId);
    }

    @Test
    void crashEvents_appendAttributionNoteIntoTaskErrorDetail() {
        long taskId = insertRunningTask();
        ingestor.ingest(List.of(Map.of(
                "taskId", taskId, "serial", serial, "pkg", "com.demo",
                "sampleCount", 5, "lastTempC", 30.0,
                "crashes", List.of(
                        Map.of("kind", "crash-java", "detail", "崩溃线程[main] 进程 com.demo"),
                        Map.of("kind", "process-lost", "detail", "被测进程消失（pkg=com.demo）")))));
        String detail = errorDetailOf(taskId);
        assertNotNull(detail, "崩溃事件必须落 error_detail 归因注记（三联图/A3 消费面禁静默蒸发）");
        assertTrue(detail.contains("崩溃线程[main]"), "崩溃 detail 原文入注记: " + detail);
        assertTrue(detail.contains("被测进程消失"), "多崩溃逐条注记: " + detail);
    }

    @Test
    void crashEvents_existingDetailAppendedNotOverwritten() {
        long taskId = insertRunningTask();
        jdbcTemplate.update("UPDATE tp_exec_task SET error_detail = '步骤 3 断言失败' WHERE id = ?", taskId);
        ingestor.ingest(List.of(Map.of(
                "taskId", taskId, "serial", serial, "sampleCount", 1,
                "crashes", List.of(Map.of("kind", "crash-native", "detail", "native 崩溃 signal 11")))));
        String detail = errorDetailOf(taskId);
        assertTrue(detail.startsWith("步骤 3 断言失败"), "原有人话详情禁被覆盖: " + detail);
        assertTrue(detail.contains("native 崩溃 signal 11"), "崩溃注记追加其后: " + detail);
    }

    @Test
    void crashEvents_taskIdMissingOrUnknown_zeroWriteNoCrash() {
        assertDoesNotThrow(() -> ingestor.ingest(List.of(Map.of(
                "serial", serial, "sampleCount", 1,
                "crashes", List.of(Map.of("kind", "crash-java", "detail", "x"))))));
        assertDoesNotThrow(() -> ingestor.ingest(List.of(Map.of(
                "taskId", 987654321L, "serial", serial, "sampleCount", 1,
                "crashes", List.of(Map.of("kind", "crash-java", "detail", "x"))))));
    }

    @Test
    void throttleSuspect_tempBreachAndCpuFull_notesTask() {
        configCacheHolder.put(AppautoConfigReader.KEY_DEVICE_CPU_FULL_PCT, "95");
        long taskId = insertRunningTask();
        ingestor.ingest(List.of(Map.of(
                "taskId", taskId, "serial", serial, "sampleCount", 20,
                "lastTempC", 38.0, "maxTempC", 43.0, "maxCpuPct", 99.0)));
        String detail = errorDetailOf(taskId);
        assertNotNull(detail, "热节流嫌疑同现必须注记进 error_detail（A3 归因维度文本面）");
        assertTrue(detail.contains("热节流嫌疑"), "注记人话含热节流嫌疑: " + detail);
        assertTrue(detail.contains("假阳"), "诚实口径=结果疑似假阳建议降温重跑: " + detail);
    }

    @Test
    void throttleSuspect_hotButIdleCpu_noTaskNote() {
        configCacheHolder.put(AppautoConfigReader.KEY_DEVICE_CPU_FULL_PCT, "95");
        long taskId = insertRunningTask();
        ingestor.ingest(List.of(Map.of(
                "taskId", taskId, "serial", serial, "sampleCount", 20,
                "maxTempC", 43.0, "maxCpuPct", 40.0)));
        assertNull(errorDetailOf(taskId), "CPU 未满载=零任务注记（禁只看温度误归因）");
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT last_overheat_at FROM tp_appauto_device WHERE serial = ?", String.class, serial),
                "越阈仍走设备过热护栏注记（两路独立）");
    }

    @Test
    void eventKey_inDict_subscribableOptional() {
        assertTrue(NotificationEventDict.all().containsKey("DEVICE_OVERHEAT"),
                "DEVICE_OVERHEAT 必须入事件字典（07 同步）");
    }
}
