package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.core.CoreApplication;
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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class NetProxyResidualIngestorTest {

    
    private static final String SERIAL = "t328p3c-emulator-5554";

    @Autowired
    private NetProxyResidualIngestor ingestor;

    @Autowired
    private AdminAlertRecipients adminAlertRecipients;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanTestRows() {
        jdbcTemplate.update(
                "DELETE FROM tp_notification WHERE event_type = 'NET_PROXY_RESIDUAL' AND dedup_key LIKE ?",
                "%t328p3c%");
    }

    @Test
    void clearedRow_alertsAllAdmins_withReadableChineseContent() {
        int admins = adminAlertRecipients.adminUserIds().size();
        assertTrue(admins > 0, "dev 库必须存在系统管理员（种子 admin）");

        ingestor.ingest(List.of(Map.of(
                "serial", SERIAL, "proxy", "10.0.2.2:18899",
                "action", "cleared", "detail", "")));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT user_id, title, content, dedup_key FROM tp_notification WHERE event_type = 'NET_PROXY_RESIDUAL'");
        assertEquals(admins, rows.size(), "cleared 对账行必须扇出全体系统管理员");
        for (Map<String, Object> r : rows) {
            String content = (String) r.get("content");
            assertTrue(content.contains(SERIAL), "正文必须含设备序列号: " + content);
            assertTrue(content.contains("10.0.2.2:18899"), "正文必须含残留代理地址: " + content);
            assertTrue(content.contains("已自动清除"), "cleared 语义=已自动清除人话: " + content);
            assertNotNull(r.get("dedup_key"), "dedup 键必填=同日窗合并单源");
        }
    }

    @Test
    void clearFailedRow_alertsAsksManualAction() {
        ingestor.ingest(List.of(Map.of(
                "serial", SERIAL, "proxy", "10.0.2.2:18899",
                "action", "clear-failed", "detail", "adb 返回非零")));

        List<String> contents = jdbcTemplate.queryForList(
                "SELECT content FROM tp_notification WHERE event_type = 'NET_PROXY_RESIDUAL'", String.class);
        assertEquals(1, contents.size());
        assertTrue(contents.get(0).contains("人工处置"), "清除未确认必须转人话人工处置: " + contents.get(0));
        assertTrue(contents.get(0).contains("adb 返回非零"), "executor 侧失败留痕必须透传: " + contents.get(0));
    }

    @Test
    void sameDayRepeat_dedupMergedNoSpam() {
        Map<String, Object> row = Map.of("serial", SERIAL, "proxy", "10.0.2.2:18899",
                "action", "cleared", "detail", "");
        ingestor.ingest(List.of(row));
        int first = countAlerts();
        ingestor.ingest(List.of(row));
        assertEquals(first, countAlerts(), "同日窗重报必须 dedup 合并不刷屏");
    }

    @Test
    void badRows_skippedHonestly_goodRowsLanded() {
        assertDoesNotThrow(() -> ingestor.ingest(List.of(
                "not-a-map",
                Map.of("proxy", "10.0.2.2:18899", "action", "cleared"),
                Map.of("serial", SERIAL, "proxy", "10.0.2.2:18899", "action", "cleared", "detail", ""))));
        assertTrue(countAlerts() >= adminAlertRecipients.adminUserIds().size(),
                "坏行逐行跳过后好行必须照常告警");
    }

    @Test
    void emptyRows_zeroAction() {
        ingestor.ingest(List.of());
        assertEquals(0, countAlerts(), "空清单=零动作（executor 诚实零行语义）");
    }

    @Test
    void eventKey_registeredInDict_notForced() {
        assertTrue(NotificationEventDict.isKnown("NET_PROXY_RESIDUAL"),
                "新事件键必须进字典单源（偏好矩阵/白名单一致）");
        assertTrue(NotificationEventDict.isDefaultEnabled("NET_PROXY_RESIDUAL"));
        org.junit.jupiter.api.Assertions.assertFalse(
                NotificationEventDict.isForced("NET_PROXY_RESIDUAL"),
                "低频可退订=非强制订阅 flags(false,false)");
    }

    private int countAlerts() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'NET_PROXY_RESIDUAL'", Long.class);
        return n == null ? 0 : n.intValue();
    }
}
