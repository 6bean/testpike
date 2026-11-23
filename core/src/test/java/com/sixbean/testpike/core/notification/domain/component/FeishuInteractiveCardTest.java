package com.sixbean.testpike.core.notification.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class FeishuInteractiveCardTest {

    private static final String KEY_ENABLED = "notify.feishu.enabled";
    private static final String KEY_WEBHOOK = "notify.feishu.webhook";
    private static final String KEY_BASE_URL = "notify.web.base-url";
    private static final String KEY_CARD = "notify.feishu.card.enabled";
    private static final String KEY_CALLBACK_KEY = "notify.feishu.callback.encrypt-key";

    @Autowired
    private NotificationService notificationService;
    @Autowired
    private ConfigCacheHolder configCacheHolder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private int port;
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private NotificationFixtureCleaner cleaner;

    @BeforeEach
    void startLedger() {
        cleaner = NotificationFixtureCleaner.forTestRun(cleaner, jdbcTemplate);
    }

    @BeforeEach
    void startStub() throws IOException {
        receivedBodies.clear();
        server = LoopbackHookServer.startHandler(this::handle);
        port = LoopbackHookServer.portOf(server);
    }

    @AfterEach
    void stopStubAndResetConfig() {
        LoopbackHookServer.stopQuietly(server);
        cleaner.cleanup();
        for (String key : List.of(KEY_ENABLED, KEY_WEBHOOK, KEY_BASE_URL, KEY_CARD, KEY_CALLBACK_KEY)) {
            configCacheHolder.remove(key);
            jdbcTemplate.update("DELETE FROM tp_sys_config WHERE config_key = ?", key);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        receivedBodies.add(body);
        byte[] resp = "{\"code\":0,\"msg\":\"success\"}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, resp.length);
        exchange.getResponseBody().write(resp);
        exchange.close();
    }

    private void setConfig(String key, String value) {
        jdbcTemplate.update("""
                INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
                VALUES (?, ?, '通知', 'string', 0, '交互卡片测试配置')
                ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value
                """, key, value);
        configCacheHolder.put(key, value);
    }

    private long seedUser() {
        return cleaner.insertUser("card-u-" + System.nanoTime(), "张三丰", 2);
    }

    private void enableChannel(String encryptKey) {
        setConfig(KEY_ENABLED, "1");
        setConfig(KEY_WEBHOOK, "http://127.0.0.1:" + port + "/hook");
        setConfig(KEY_BASE_URL, "http://127.0.0.1:5173");
        setConfig(KEY_CARD, "1");
        setConfig(KEY_CALLBACK_KEY, encryptKey);
    }

    private void awaitBody() throws InterruptedException {
        for (int i = 0; i < 60 && receivedBodies.isEmpty(); i++) {
            Thread.sleep(100);
        }
    }


    @Test
    void 卡片开_催办事件_出interactive卡_深链review_已读按钮携签发令牌() throws Exception {
        long userId = seedUser();
        enableChannel("cb-key-252");
        long notifId = notificationService.create(new NotificationCreateInput(userId, null,
                "REVIEW_REMIND", "评审催办：TC-001", "用例「登录」分配给你已超 24 小时未评审。",
                "card-remind-" + System.nanoTime()));
        awaitBody();

        assertEquals(1, receivedBodies.size(), "催办事件应恰好一次外呼");
        JsonNode payload = mapper.readTree(receivedBodies.get(0));
        assertEquals("interactive", payload.path("msg_type").asText(), "TC-E11-01：card 开=出交互卡片");
        JsonNode card = payload.path("card");
        assertTrue(card.path("header").path("title").path("content").asText().contains("评审催办"),
                "卡片标题头=通知标题: " + card.path("header"));
        String cardJson = card.toString();
        assertTrue(cardJson.contains("http://127.0.0.1:5173/review"), "去处理深链=base-url+/review: " + cardJson);
        assertTrue(cardJson.contains("去处理"), "含「去处理」按钮");
        assertTrue(cardJson.contains("标记已读"), "含「标记已读」回传按钮");
        assertTrue(cardJson.contains(String.valueOf(notifId)), "回传 value 内嵌站内信 ID: " + cardJson);
        assertTrue(cardJson.contains(String.valueOf(userId)), "回传 value 内嵌收件人 ID");
        JsonNode readBtn = findReadButton(card);
        assertNotNull(readBtn, "存在标记已读按钮");
        JsonNode value = readBtn.path("value");
        String content = value.path("payload").asText();
        assertTrue(com.sixbean.testpike.common.notify.ReadTokenSigner
                .verify(content, value.path("sig").asText(), "cb-key-252"),
                "已读令牌 sig 须与 encrypt-key HMAC 单源可对: " + value);
        assertTrue(content.contains(notifId + ":" + userId), "签名内容含 {notificationId}:{userId}: " + content);
    }

    @Test
    void 卡片开_超时升级事件_深链review() throws Exception {
        long userId = seedUser();
        enableChannel("cb-key-252");
        notificationService.create(new NotificationCreateInput(userId, null,
                "REVIEW_TIMEOUT", "评审超时：TC-002", "用例「下单」评审已超 72 小时未处理。",
                "card-timeout-" + System.nanoTime()));
        awaitBody();
        assertEquals(1, receivedBodies.size());
        JsonNode payload = mapper.readTree(receivedBodies.get(0));
        assertEquals("interactive", payload.path("msg_type").asText());
        assertTrue(payload.path("card").toString().contains("http://127.0.0.1:5173/review"),
                "超时升级深链=/review");
    }

    @Test
    void 卡片开_自动建单待指派_深链defects_事件字典已登记() throws Exception {
        assertTrue(NotificationEventDict.isKnown("DEFECT_UNASSIGNED"),
                "TC-E11-01 前置：DEFECT_UNASSIGNED 须入事件字典");
        long userId = seedUser();
        enableChannel("cb-key-252");
        notificationService.create(new NotificationCreateInput(userId, null,
                "DEFECT_UNASSIGNED", "自动建单待指派：登录用例", "缺陷 #9 暂无可指派人。",
                "card-defect-" + System.nanoTime()));
        awaitBody();
        assertEquals(1, receivedBodies.size());
        JsonNode payload = mapper.readTree(receivedBodies.get(0));
        assertEquals("interactive", payload.path("msg_type").asText());
        assertTrue(payload.path("card").toString().contains("http://127.0.0.1:5173/defects"),
                "建单待指派深链=/defects");
    }


    @Test
    void 卡片关_三事件仍走post_存量零变化() throws Exception {
        long userId = seedUser();
        enableChannel("cb-key-252");
        setConfig(KEY_CARD, "0");
        notificationService.create(new NotificationCreateInput(userId, null,
                "REVIEW_REMIND", "评审催办：TC-003", "内容", "card-off-" + System.nanoTime()));
        awaitBody();
        assertEquals(1, receivedBodies.size());
        JsonNode payload = mapper.readTree(receivedBodies.get(0));
        assertEquals("post", payload.path("msg_type").asText(),
                "TC-E11-01：card.enabled 关=post 现状零变化");
    }


    @Test
    void 卡片开_白名单外事件_仍走post() throws Exception {
        long userId = seedUser();
        enableChannel("cb-key-252");
        notificationService.create(new NotificationCreateInput(userId, null,
                "LOGIN_LOCKED", "账号锁定通知", "连续登录失败已锁定", "card-lock-" + System.nanoTime()));
        awaitBody();
        assertEquals(1, receivedBodies.size());
        JsonNode payload = mapper.readTree(receivedBodies.get(0));
        assertEquals("post", payload.path("msg_type").asText(),
                "E11 卡片白名单=三事件，白名单外不动存量 post");
    }


    @Test
    void 卡片含收件人显示名文本面() throws Exception {
        long userId = seedUser();
        enableChannel("cb-key-252");
        notificationService.create(new NotificationCreateInput(userId, null,
                "REVIEW_REMIND", "评审催办：TC-004", "内容", "card-at-" + System.nanoTime()));
        awaitBody();
        assertEquals(1, receivedBodies.size());
        assertTrue(receivedBodies.get(0).contains("张三丰"),
                "@负责人文本面=收件人显示名（无企业目录映射=纯文本，如实标注）");
    }

    private static JsonNode findReadButton(JsonNode card) {
        for (JsonNode el : card.path("elements")) {
            for (JsonNode btn : el.path("actions")) {
                if ("标记已读".equals(btn.path("text").path("content").asText())) {
                    return btn;
                }
            }
        }
        return null;
    }
}
