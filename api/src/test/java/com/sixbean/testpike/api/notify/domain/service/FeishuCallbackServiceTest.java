package com.sixbean.testpike.api.notify.domain.service;

import com.sixbean.testpike.api.ApiApplication;
import com.sixbean.testpike.api.notify.domain.component.CallbackRateLimiter;
import com.sixbean.testpike.api.notify.domain.component.FeishuCallbackVerifier;
import com.sixbean.testpike.api.notify.domain.component.NonceReplayGuard;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.idempotent.IdempotentGuard;
import com.sixbean.testpike.common.notify.ReadTokenSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = ApiApplication.class)
class FeishuCallbackServiceTest {

    private static final String KEY = "test-callback-encrypt-key-" + UUID.randomUUID();

    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private IdempotentGuard idempotentGuard;

    
    private StubNotificationReadClient readClient;
    
    private java.util.List<String> auditDenied;

    private FeishuCallbackService service;

    @BeforeEach
    void setUp() {
        readClient = new StubNotificationReadClient();
        auditDenied = new java.util.ArrayList<>();
        service = new FeishuCallbackService(
                new FeishuCallbackVerifier(() -> KEY),
                new NonceReplayGuard(redis, () -> NonceReplayGuard.DEFAULT_TTL_SECONDS),
                idempotentGuard,
                readClient,
                auditDenied::add,
                new CallbackRateLimiter(redis, () -> 100, () -> CallbackRateLimiter.DEFAULT_WINDOW_SECONDS));
    }

    
    private String legalBody(long notificationId, long userId, String eventId, String nonce,
                             String ts, String sigOfToken, String key) {
        String tokenPayload = notificationId + ":" + userId + ":" + ts;
        String sig = sigOfToken == null
                ? ReadTokenSigner.sign(tokenPayload, key) : sigOfToken;
        return "{\"event_id\":\"" + eventId + "\",\"action_value\":{\"payload\":\""
                + tokenPayload + "\",\"sig\":\"" + sig + "\"}}";
    }

    private String headerTs() {
        return String.valueOf(System.currentTimeMillis() / 1000);
    }

    @Test
    void 合法回调_标记已读写入core并放行() {
        String ts = headerTs();
        String nonce = UUID.randomUUID().toString();
        String body = legalBody(42L, 7L, "evt-" + nonce, nonce, ts, null, KEY);
        String sig = FeishuCallbackVerifier.sign(ts, nonce, body, KEY);
        service.handle(body, ts, nonce, sig, "127.0.0.1");
        assertEquals(42L, readClient.lastId, "站内信 id 须透传 core");
        assertEquals(7L, readClient.lastUserId, "收件人须取令牌内嵌 userId（sig 绑定防伪造）");
        assertTrue(auditDenied.isEmpty(), "成功回调不得记拒绝审计");
    }

    @Test
    void 签名错误_401拒绝且零写入有审计() {
        String ts = headerTs();
        String nonce = UUID.randomUUID().toString();
        String body = legalBody(42L, 7L, "evt-" + nonce, nonce, ts, null, KEY);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.handle(body, ts, nonce, "ZmFrZS1zaWduYXR1cmU=", "127.0.0.1"));
        assertEquals(40101, ex.getCode(), "UNAUTHORIZED=40101（HTTP 401 映射）");
        assertTrue(readClient.calls == 0, "验签失败=core 写入零调用（先验签后写库）");
        assertEquals(1, auditDenied.size(), "拒绝必留审计痕");
    }

    @Test
    void 时间戳窗外_401拒绝() {
        String oldTs = String.valueOf(System.currentTimeMillis() / 1000 - 400);
        String nonce = UUID.randomUUID().toString();
        String body = legalBody(1L, 1L, "evt", nonce, oldTs, null, KEY);
        String sig = FeishuCallbackVerifier.sign(oldTs, nonce, body, KEY);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.handle(body, oldTs, nonce, sig, "127.0.0.1"));
        assertEquals(40101, ex.getCode());
        assertEquals(0, readClient.calls);
    }

    @Test
    void nonce重放_首发过二次拒且只动一次() {
        String ts = headerTs();
        String nonce = "replay-" + UUID.randomUUID();
        String body = legalBody(9L, 3L, "evt-" + nonce, nonce, ts, null, KEY);
        String sig = FeishuCallbackVerifier.sign(ts, nonce, body, KEY);
        service.handle(body, ts, nonce, sig, "127.0.0.1");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.handle(body, ts, nonce, sig, "127.0.0.1"));
        assertEquals(40101, ex.getCode(), "重放拒绝口径与验签同级");
        assertEquals(1, readClient.calls, "重放二发不得触达 core");
    }

    @Test
    void 已读令牌被篡改_403拒绝且零写入() {
        String ts = headerTs();
        String nonce = UUID.randomUUID().toString();
        String body = legalBody(42L, 7L, "evt-" + nonce, nonce, ts,
                ReadTokenSigner.sign("42:999:" + ts, KEY), KEY);
        String sig = FeishuCallbackVerifier.sign(ts, nonce, body, KEY);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.handle(body, ts, nonce, sig, "127.0.0.1"));
        assertEquals(40301, ex.getCode(), "令牌与动作不一致=授权拒");
        assertEquals(0, readClient.calls);
        assertEquals(1, auditDenied.size());
    }

    @Test
    void 同事件重复回调_幂等只标记一次() {
        long id = 77L;
        String userId = "5";
        String eventId = "dup-" + UUID.randomUUID();
        String ts = headerTs();
        String n1 = UUID.randomUUID().toString();
        String n2 = UUID.randomUUID().toString();
        String b1 = legalBody(id, Long.parseLong(userId), eventId, n1, ts, null, KEY);
        String b2 = legalBody(id, Long.parseLong(userId), eventId, n2, ts, null, KEY);
        service.handle(b1, ts, n1, FeishuCallbackVerifier.sign(ts, n1, b1, KEY), "127.0.0.1");
        service.handle(b2, ts, n2, FeishuCallbackVerifier.sign(ts, n2, b2, KEY), "127.0.0.1");
        assertEquals(1, readClient.calls, "同 event_id 二次回调幂等=只动一次（IT-E11-01）");
    }

    @Test
    void 密钥缺省_failClosed拒绝一切回调() {
        FeishuCallbackService noKey = new FeishuCallbackService(
                new FeishuCallbackVerifier(() -> ""),
                new NonceReplayGuard(redis, () -> NonceReplayGuard.DEFAULT_TTL_SECONDS), idempotentGuard, readClient,
                m -> {
                }, new CallbackRateLimiter(redis, () -> 100, () -> CallbackRateLimiter.DEFAULT_WINDOW_SECONDS));
        String ts = headerTs();
        String nonce = UUID.randomUUID().toString();
        String body = legalBody(1L, 1L, "evt", nonce, ts, null, KEY);
        String sig = FeishuCallbackVerifier.sign(ts, nonce, body, KEY);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> noKey.handle(body, ts, nonce, sig, "127.0.0.1"));
        assertEquals(40101, ex.getCode());
        assertEquals(0, readClient.calls);
    }

    @Test
    void core写入失败_异常透传人话不吞不假成功() {
        readClient.failWith = new ServiceException("站内信不存在或无权访问", 40401);
        String ts = headerTs();
        String nonce = UUID.randomUUID().toString();
        String body = legalBody(404L, 7L, "evt-" + nonce, nonce, ts, null, KEY);
        String sig = FeishuCallbackVerifier.sign(ts, nonce, body, KEY);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.handle(body, ts, nonce, sig, "127.0.0.1"));
        assertEquals(40401, ex.getCode());
    }

    @Test
    void 非法JSON体_400人话() {
        String ts = headerTs();
        String nonce = UUID.randomUUID().toString();
        String sig = FeishuCallbackVerifier.sign(ts, nonce, "not-json", KEY);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.handle("not-json", ts, nonce, sig, "127.0.0.1"));
        assertEquals(40001, ex.getCode());
        assertEquals(0, readClient.calls);
    }

    @Test
    void 限流超限_拒绝且零写入() {
        String ip = "lim-" + UUID.randomUUID();
        FeishuCallbackService tight = new FeishuCallbackService(
                new FeishuCallbackVerifier(() -> KEY),
                new NonceReplayGuard(redis, () -> NonceReplayGuard.DEFAULT_TTL_SECONDS), idempotentGuard, readClient,
                m -> {
                }, new CallbackRateLimiter(redis, () -> 1, () -> CallbackRateLimiter.DEFAULT_WINDOW_SECONDS));
        String ts = headerTs();
        String n1 = UUID.randomUUID().toString();
        String body = legalBody(2L, 8L, "evt-" + n1, n1, ts, null, KEY);
        String sig = FeishuCallbackVerifier.sign(ts, n1, body, KEY);
        tight.handle(body, ts, n1, sig, ip);
        String n2 = UUID.randomUUID().toString();
        String body2 = legalBody(2L, 8L, "evt-" + n2, n2, ts, null, KEY);
        ServiceException ex = assertThrows(ServiceException.class, () -> tight.handle(body2, ts, n2,
                FeishuCallbackVerifier.sign(ts, n2, body2, KEY), ip));
        assertEquals(42901, ex.getCode());
        assertEquals(1, readClient.calls, "超限请求不得触达 core");
    }

    
    private static final class StubNotificationReadClient
            implements com.sixbean.testpike.api.notify.infrastructure.remote.client.NotificationReadClient {
        long lastId;
        long lastUserId;
        int calls;
        RuntimeException failWith;

        @Override
        public void markRead(long notificationId, long userId) {
            calls++;
            if (failWith != null) {
                throw failWith;
            }
            lastId = notificationId;
            lastUserId = userId;
        }
    }
}
