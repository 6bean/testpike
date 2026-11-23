package com.sixbean.testpike.core.notification.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.support.NotificationFixtureCleaner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class NotificationWebhookTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    private NotificationFixtureCleaner cleaner;
    private HttpServer server;
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger receivedCount = new AtomicInteger();
    private int port;

    @BeforeEach
    void startLedger() {
        cleaner = NotificationFixtureCleaner.forTestRun(cleaner, jdbcTemplate);
    }

    @BeforeEach
    void startWebhookServer() throws IOException {
        receivedBodies.clear();
        receivedCount.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", this::handleWebhook);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopWebhookServer() {
        if (server != null) {
            server.stop(0);
        }
        cleaner.cleanup();
    }

    /**
     * The two webhook config rows are shipped factory rows (url='' / enabled='false').
     * Tests above overwrite them in place, so the original values are captured before
     * each test and restored afterwards: the dev database must not keep a dead test
     * endpoint, otherwise the async retry sender keeps POSTing to a closed port after
     * the suite and pollutes both the shared baseline and the log stream.
     */
    @BeforeEach
    void snapshotWebhookFactoryRows() {
        factoryEnabled = readConfigRow(KEY_ENABLED);
        factoryUrl = readConfigRow(KEY_URL);
    }

    @AfterEach
    void restoreWebhookFactoryRows() {
        writeBack(KEY_ENABLED, factoryEnabled);
        writeBack(KEY_URL, factoryUrl);
    }

    private String factoryEnabled;
    private String factoryUrl;

    private String readConfigRow(String key) {
        List<String> rows = jdbcTemplate.query(
                "SELECT config_value FROM tp_sys_config WHERE config_key = ?",
                (rs, i) -> rs.getString(1), key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void writeBack(String key, String value) {
        if (value == null) {
            jdbcTemplate.update("DELETE FROM tp_sys_config WHERE config_key = ?", key);
            configCacheHolder.remove(key);
            return;
        }
        jdbcTemplate.update("UPDATE tp_sys_config SET config_value = ? WHERE config_key = ?", value, key);
        configCacheHolder.put(key, value);
    }

    private void handleWebhook(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        receivedBodies.add(body);
        receivedCount.incrementAndGet();
        int code = Integer.parseInt(exchange.getRequestHeaders().getFirst("X-Test-Code") == null
                ? "200" : exchange.getRequestHeaders().getFirst("X-Test-Code"));
        byte[] resp = "ok".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, resp.length);
        exchange.getResponseBody().write(resp);
        exchange.close();
    }

    private void setConfig(String key, String value) {
        jdbcTemplate.update("""
                INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description, created_by)
                VALUES (?, ?, '通知', 'string', 0, 'webhook 测试配置', 1)
                ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value
                """, key, value);
        configCacheHolder.put(key, value);
    }

    private static final String KEY_ENABLED = "testpike.notify.webhook-enabled";
    private static final String KEY_URL = "testpike.notify.webhook-url";

    private NotificationCreateInput input() {
        return new NotificationCreateInput(1L, null, "WEBHOOK_TEST",
                "webhook 测试通知", "这是一条 webhook 测试内容", "wh-test-" + System.nanoTime());
    }

    @Test
    void webhook启用_站内加webhook双通道收到() throws Exception {
        setConfig(KEY_ENABLED, "1");
        setConfig(KEY_URL, "http://127.0.0.1:" + port + "/webhook");

        long id = notificationService.create(input());
        cleaner.trackNotification(id);

        Long inDb = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE id = ?", Long.class, id);
        assertEquals(1, inDb, "TC-N5-01：站内信应落库");
        waitFor(() -> receivedCount.get() >= 1, 5000, "webhook 应收到请求");
        String body = receivedBodies.get(0);
        assertTrue(body.contains("WEBHOOK_TEST"), "TC-N5-01：webhook body 应含 eventType: " + body);
        assertTrue(body.contains("webhook 测试通知"), "TC-N5-01：webhook body 应含 title: " + body);
        assertTrue(body.contains("webhook 测试内容"), "TC-N5-01：webhook body 应含 content: " + body);
    }

    @Test
    void webhook失败_重试3次_站内信降级仍成功() throws Exception {
        setConfig(KEY_ENABLED, "1");
        setConfig(KEY_URL, "http://127.0.0.1:" + port + "/webhook?fail=1");
        server.removeContext("/webhook");
        server.createContext("/webhook", exchange -> {
            exchange.getRequestBody().readAllBytes();
            receivedCount.incrementAndGet();
            byte[] resp = "err".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });

        long id = notificationService.create(input());
        cleaner.trackNotification(id);

        Long inDb = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE id = ?", Long.class, id);
        assertEquals(1, inDb, "IT-N5-01：webhook 失败不应影响站内信落库");
        waitFor(() -> receivedCount.get() >= 4, 8000, "应重试 3 次（共 4 次请求）");
        assertEquals(4, receivedCount.get(), "IT-N5-01：失败应重试 3 次（共 4 次尝试）");
    }

    @Test
    void webhook未启用_仅站内信() throws InterruptedException {
        setConfig(KEY_ENABLED, "0");
        setConfig(KEY_URL, "http://127.0.0.1:" + port + "/webhook");

        long id = notificationService.create(input());
        cleaner.trackNotification(id);

        Long inDb = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE id = ?", Long.class, id);
        assertEquals(1, inDb, "站内信应照常");
        Thread.sleep(1500);
        assertEquals(0, receivedCount.get(), "未启用不应发送 webhook");
    }

    @Test
    void webhook启用但无URL_降级仅站内() throws InterruptedException {
        setConfig(KEY_ENABLED, "1");
        jdbcTemplate.update("DELETE FROM tp_sys_config WHERE config_key = '" + KEY_URL + "'");
        configCacheHolder.remove(KEY_URL);

        long id = notificationService.create(input());
        cleaner.trackNotification(id);

        Long inDb = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE id = ?", Long.class, id);
        assertEquals(1, inDb, "无 URL 时站内信仍应成功（降级）");
        Thread.sleep(1500);
        assertEquals(0, receivedCount.get(), "无 URL 不应发送 webhook");
    }

    private void waitFor(Check cond, long timeoutMs, String msg) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.check()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("等待超时: " + msg);
    }

    @FunctionalInterface
    private interface Check {
        boolean check();
    }
}
