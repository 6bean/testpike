package com.sixbean.testpike.core.notification.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import com.sixbean.testpike.core.support.LoopbackHookServer;
import com.sixbean.testpike.core.support.NotificationFixtureCleaner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class FeishuWebhookChannelTest {

    static final String KEY_ENABLED = "notify.feishu.enabled";
    static final String KEY_WEBHOOK = "notify.feishu.webhook";
    static final String KEY_SECRET = "notify.feishu.secret";
    static final String KEY_BASE_URL = "notify.web.base-url";

    @Autowired
    private FeishuWebhookChannel feishuWebhookChannel;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private ConfigCacheHolder configCacheHolder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private HttpServer server;
    private int port;
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
    private final AtomicInteger receivedCount = new AtomicInteger();
    
    private volatile boolean stubFail = false;
    private NotificationFixtureCleaner cleaner;

    @BeforeEach
    void startLedger() {
        cleaner = NotificationFixtureCleaner.forTestRun(cleaner, jdbcTemplate);
        // The three create-side events below stamp their dedup keys with this prefix.
        cleaner.trackDedupPrefix("feishu-");
    }

    @BeforeEach
    void startStub() throws IOException {
        receivedBodies.clear();
        lastHeaders.clear();
        receivedCount.set(0);
        stubFail = false;
        server = LoopbackHookServer.startHandler(this::handle);
        port = LoopbackHookServer.portOf(server);
    }

    @AfterEach
    void stopChannelStubAndConfigs() {
        LoopbackHookServer.stopQuietly(server);
        cleaner.cleanup();
        for (String key : List.of(KEY_ENABLED, KEY_WEBHOOK, KEY_SECRET, KEY_BASE_URL)) {
            configCacheHolder.remove(key);
            jdbcTemplate.update("DELETE FROM tp_sys_config WHERE config_key = ?", key);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!stubFail) {
            receivedBodies.add(body);
            String tsHeader = exchange.getRequestHeaders().getFirst("X-Lark-Request-Timestamp");
            String signHeader = exchange.getRequestHeaders().getFirst("X-Lark-Request-Sign");
            if (tsHeader != null) {
                lastHeaders.put("timestamp", tsHeader);
            }
            if (signHeader != null) {
                lastHeaders.put("sign", signHeader);
            }
        }
        receivedCount.incrementAndGet();
        byte[] resp = stubFail ? "err".getBytes(StandardCharsets.UTF_8)
                : "{\"code\":0,\"msg\":\"success\"}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(stubFail ? 500 : 200, resp.length);
        exchange.getResponseBody().write(resp);
        exchange.close();
    }

    private void setConfig(String key, String value) {
        jdbcTemplate.update("""
                INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
                VALUES (?, ?, '通知', 'string', 0, '群机器人测试配置')
                ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value
                """, key, value);
        configCacheHolder.put(key, value);
    }

    private String stubUrl() {
        return "http://127.0.0.1:" + port + "/hook";
    }

    
    private long seedBatch() {
        Long projectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code) VALUES ('飞书渠道项目',"
                        + " 'F6-' || (extract(epoch from now())*10000)::bigint) RETURNING id", Long.class);
        assert projectId != null;
        cleaner.trackProject(projectId);
        Long batchId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_batch (project_id, batch_no, name, status, type) VALUES (?, ?, '回归-夜间批', 6, 1) RETURNING id",
                Long.class, projectId, "FB-" + System.nanoTime());
        assert batchId != null;
        for (int i = 0; i < 3; i++) {
            jdbcTemplate.update(
                    "INSERT INTO tp_exec_task (project_id, case_id, batch_id, status) VALUES (?, 999997, ?, ?)",
                    projectId, batchId, i == 0 ? 3 : 4);
        }
        return batchId;
    }


    @Test
    void 开关开_批次候选端点_post富文本含批次通过率明细链接_签名校验通过() throws Exception {
        long batchId = seedBatch();
        setConfig(KEY_ENABLED, "1");
        setConfig(KEY_WEBHOOK, stubUrl());
        setConfig(KEY_SECRET, "s3cr3t-k8");
        setConfig(KEY_BASE_URL, "http://127.0.0.1:5173");

        boolean sent = feishuWebhookChannel.sendBatchNotice(batchId);

        assertTrue(sent, "TC-V6-01：开关开应发送成功");
        assertEquals(1, receivedCount.get(), "TC-V6-01：应恰好一次外呼");
        String body = receivedBodies.get(0);
        assertTrue(body.contains("msg_type") && body.contains("post"),
                "TC-V6-01：payload=飞书 post 富文本协议: " + body);
        assertTrue(body.contains("回归-夜间批"), "TC-V6-01：消息含批次名: " + body);
        assertTrue(body.contains("通过率"), "TC-V6-01：消息含通过率字段: " + body);
        assertTrue(body.contains("http://127.0.0.1:5173/batches/" + batchId),
                "TC-V6-01：明细链接=base-url+/batches/{id}: " + body);
        String ts = lastHeaders.get("timestamp");
        String sign = lastHeaders.get("sign");
        assertNotNull(ts, "定案C：secret 非空须携 X-Lark-Request-Timestamp");
        assertNotNull(sign, "定案C：secret 非空须携 X-Lark-Request-Sign");
        assertEquals(expectedSign(ts, "s3cr3t-k8"), sign, "定案C：签名须与飞书群机器人协议一致");
    }

    @Test
    void secret空_无签名头_仍发送成功() {
        long batchId = seedBatch();
        setConfig(KEY_ENABLED, "1");
        setConfig(KEY_WEBHOOK, stubUrl());

        boolean sent = feishuWebhookChannel.sendBatchNotice(batchId);

        assertTrue(sent, "TC-V6-01：secret 空=无签名模式仍应发送");
        assertEquals(1, receivedCount.get());
        assertEquals(null, lastHeaders.get("timestamp"), "secret 空不得携签名头");
    }

    @Test
    void 开关关_零外呼_返回false() throws InterruptedException {
        long batchId = seedBatch();
        setConfig(KEY_ENABLED, "0");
        setConfig(KEY_WEBHOOK, stubUrl());

        boolean sent = feishuWebhookChannel.sendBatchNotice(batchId);

        assertFalse(sent, "TC-V6-01：关闭=零外呼（默认行为不变）");
        Thread.sleep(500);
        assertEquals(0, receivedCount.get(), "开关关不得产生任何 HTTP 外呼");
    }


    @Test
    void 出站失败_重试3次后降级_不抛出() {
        long batchId = seedBatch();
        setConfig(KEY_ENABLED, "1");
        setConfig(KEY_WEBHOOK, stubUrl());
        stubFail = true;

        boolean sent = feishuWebhookChannel.sendBatchNotice(batchId);

        assertFalse(sent, "IT-N5-01 同口径：全败返回 false 降级");
        assertEquals(4, receivedCount.get(), "红线⑥骨架：4 次尝试=首次+重试 3 次");
    }


    @Test
    void 全局开_非批次族create直通_批次族不直通防双发() throws InterruptedException {
        setConfig(KEY_ENABLED, "1");
        setConfig(KEY_WEBHOOK, stubUrl());

        notificationService.create(new NotificationCreateInput(1L, null, "LOGIN_LOCKED",
                "账号锁定通知", "连续登录失败已锁定", "feishu-direct-" + System.nanoTime()));
        for (int i = 0; i < 50 && receivedCount.get() < 1; i++) {
            Thread.sleep(100);
        }
        assertTrue(receivedCount.get() >= 1, "定案D：非批次族应全局直通 feishu");
        assertTrue(receivedBodies.get(0).contains("账号锁定通知"), "直通消息含标题");

        notificationService.create(new NotificationCreateInput(1L, null, "BATCH_FAILED",
                "批次失败通知", "内容", "feishu-batch-" + System.nanoTime()));
        notificationService.create(new NotificationCreateInput(1L, null, "BATCH_FINISHED",
                "批次完成通知", "内容", "feishu-done-" + System.nanoTime()));
        Thread.sleep(1000);
        assertEquals(1, receivedCount.get(), "定案D：批次族=create 内零直通（发信侧控制，防双发绕开关）");
    }


    @Test
    void 读侧二次校验_http非loopback与元数据端点_拒发零请求() {
        long batchId = seedBatch();
        setConfig(KEY_ENABLED, "1");
        configCacheHolder.put(KEY_WEBHOOK, "http://10.0.0.8/hook");
        assertFalse(feishuWebhookChannel.sendBatchNotice(batchId),
                "IT-V6-01：http 非 loopback 地址读侧二次拒发");
        configCacheHolder.put(KEY_WEBHOOK, "https://169.254.169.254/hook");
        assertFalse(feishuWebhookChannel.sendBatchNotice(batchId),
                "IT-V6-01：云元数据端点读侧二次拒发");
        assertEquals(0, receivedCount.get(), "拒发=零 HTTP 尝试");
    }


    private static String expectedSign(String timestamp, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(
                mac.doFinal((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8)));
    }
}
