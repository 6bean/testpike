package com.sixbean.testpike.api.notify.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.api.notify.domain.component.CallbackDecision;
import com.sixbean.testpike.api.notify.domain.component.CallbackRateLimiter;
import com.sixbean.testpike.api.notify.domain.component.FeishuCallbackVerifier;
import com.sixbean.testpike.api.notify.domain.component.NonceReplayGuard;
import com.sixbean.testpike.api.notify.infrastructure.remote.client.NotificationReadClient;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.idempotent.IdempotentGuard;
import com.sixbean.testpike.common.notify.ReadTokenSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;


public class FeishuCallbackService {

    private static final Logger log = LoggerFactory.getLogger(FeishuCallbackService.class);

    private final FeishuCallbackVerifier verifier;
    private final NonceReplayGuard replayGuard;
    private final IdempotentGuard idempotentGuard;
    private final NotificationReadClient readClient;
    private final Consumer<String> deniedAuditor;
    private final CallbackRateLimiter rateLimiter;
    private final ObjectMapper mapper = new ObjectMapper();

    public FeishuCallbackService(FeishuCallbackVerifier verifier, NonceReplayGuard replayGuard,
                                 IdempotentGuard idempotentGuard, NotificationReadClient readClient,
                                 Consumer<String> deniedAuditor, CallbackRateLimiter rateLimiter) {
        this.verifier = verifier;
        this.replayGuard = replayGuard;
        this.idempotentGuard = idempotentGuard;
        this.readClient = readClient;
        this.deniedAuditor = deniedAuditor;
        this.rateLimiter = rateLimiter;
    }

    
    public void handle(String rawBody, String ts, String nonce, String sig, String ip) {
        CallbackDecision budget = rateLimiter.tryAcquire(ip == null ? "unknown" : ip);
        if (!budget.allowed()) {
            throw new ServiceException(budget.message(), BizErrorCode.RATE_LIMITED);
        }
        if (!verifier.verifyTimestamp(ts)) {
            deny("时间戳缺失或超出 ±5 分钟容差", ts, nonce);
        }
        if (!verifier.verify(ts, nonce, rawBody, sig)) {
            deny("回调签名校验失败（密钥不匹配/体被篡改/密钥未配置）", ts, nonce);
        }
        if (!replayGuard.firstSeen(nonce)) {
            deny("nonce 重放（同随机数二次回调）", ts, nonce);
        }
        JsonNode body = parse(rawBody, ts, nonce);
        String eventId = body.path("event_id").asText("");
        JsonNode action = body.path("action_value");
        String tokenPayload = action.path("payload").asText("");
        String tokenSig = action.path("sig").asText("");
        if (eventId.isBlank() || tokenPayload.isBlank()) {
            deny("回调体缺 event_id 或按钮回传值", ts, nonce);
        }
        if (!ReadTokenSigner.verify(tokenPayload, tokenSig, verifier.encryptKey())) {
            deny(403, "已读令牌验签失败（payload 与签名不一致=伪造/篡改）", ts, nonce);
        }
        long[] bound = parseToken(tokenPayload, ts, nonce);
        long notificationId = bound[0];
        long userId = bound[1];
        idempotentGuard.execute("feishu-cb:" + eventId, () -> {
            readClient.markRead(notificationId, userId);
            return null;
        });
        log.info("飞书回调标记已读成功 notificationId={} userId={} eventId={}",
                notificationId, userId, eventId);
    }

    private JsonNode parse(String rawBody, String ts, String nonce) {
        try {
            return mapper.readTree(rawBody == null ? "" : rawBody);
        } catch (Exception e) {
            deny(400, "回调体不是合法 JSON", ts, nonce);
            throw new IllegalStateException("unreachable");
        }
    }

    private long[] parseToken(String payload, String ts, String nonce) {
        String[] parts = payload.split(":");
        try {
            if (parts.length != 3) {
                throw new NumberFormatException("segments");
            }
            return new long[]{Long.parseLong(parts[0]), Long.parseLong(parts[1])};
        } catch (NumberFormatException e) {
            deny(400, "已读令牌格式非法（应为 站内信ID:收件人ID:时间戳）", ts, nonce);
            throw new IllegalStateException("unreachable");
        }
    }

    
    private void deny(String reason, String ts, String nonce) {
        deny(401, reason, ts, nonce);
    }

    
    private void deny(int httpCode, String reason, String ts, String nonce) {
        try {
            deniedAuditor.accept(reason + "（ts=" + ts + " nonce=" + nonce + "）");
        } catch (RuntimeException ex) {
            log.warn("飞书回调拒绝审计写入失败（不阻断拒绝判定）：{}", ex.getMessage());
        }
        int bizCode = switch (httpCode) {
            case 400 -> BizErrorCode.VALIDATION_ERROR;
            case 403 -> BizErrorCode.FORBIDDEN;
            default -> BizErrorCode.UNAUTHORIZED;
        };
        throw new ServiceException("回调校验失败：" + reason, bizCode);
    }
}
