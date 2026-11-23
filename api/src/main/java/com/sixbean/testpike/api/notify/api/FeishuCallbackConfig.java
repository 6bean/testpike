package com.sixbean.testpike.api.notify.api;

import com.sixbean.testpike.api.mcp.infrastructure.remote.RestCoreConfigClient;
import com.sixbean.testpike.api.notify.domain.component.CallbackRateLimiter;
import com.sixbean.testpike.api.notify.domain.component.FeishuCallbackVerifier;
import com.sixbean.testpike.api.notify.domain.component.NonceReplayGuard;
import com.sixbean.testpike.api.notify.domain.service.FeishuCallbackService;
import com.sixbean.testpike.api.notify.infrastructure.remote.client.NotificationReadClient;
import com.sixbean.testpike.api.notify.infrastructure.remote.client.RestCallbackDeniedAuditWriter;
import com.sixbean.testpike.common.idempotent.IdempotentGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.function.Supplier;


@Configuration
public class FeishuCallbackConfig {

    private static final Logger log = LoggerFactory.getLogger(FeishuCallbackConfig.class);

 
    public static final String KEY_ENCRYPT = "notify.feishu.callback.encrypt-key";
    
    public static final String KEY_RATE_LIMIT = "notify.feishu.callback.rate-limit/min";
    static final int DEFAULT_RATE_LIMIT = 60;

    
    @Bean
    public Supplier<String> feishuEncryptKey(RestCoreConfigClient configClient) {
        return () -> {
            try {
                return configClient.value(KEY_ENCRYPT, "");
            } catch (RuntimeException ex) {
                log.warn("飞书回调密钥读取失败，fail-closed 拒绝一切回调：{}", ex.getMessage());
                return "";
            }
        };
    }

    @Bean
    public FeishuCallbackVerifier feishuCallbackVerifier(Supplier<String> feishuEncryptKey) {
        return new FeishuCallbackVerifier(feishuEncryptKey);
    }

 
    @Bean
    public NonceReplayGuard nonceReplayGuard(StringRedisTemplate redis,
                                             RestCoreConfigClient configClient) {
        return new NonceReplayGuard(redis, configClient.secondsSupplier(
                NonceReplayGuard.TTL_KEY, NonceReplayGuard.DEFAULT_TTL_SECONDS));
    }

    @Bean
    public CallbackRateLimiter callbackRateLimiter(StringRedisTemplate redis,
                                                   RestCoreConfigClient configClient) {
        return new CallbackRateLimiter(redis, () -> {
            try {
                int parsed = Integer.parseInt(configClient.value(KEY_RATE_LIMIT, "60").trim());
                return parsed > 0 ? parsed : DEFAULT_RATE_LIMIT;
            } catch (RuntimeException ex) {
                log.warn("飞书回调限额读取失败或非法，回退默认 {}：{}", DEFAULT_RATE_LIMIT, ex.getMessage());
                return DEFAULT_RATE_LIMIT;
            }
        }, configClient.secondsSupplier(CallbackRateLimiter.WINDOW_KEY,
                CallbackRateLimiter.DEFAULT_WINDOW_SECONDS));
    }

    @Bean
    public FeishuCallbackService feishuCallbackService(FeishuCallbackVerifier verifier,
                                                       NonceReplayGuard replayGuard,
                                                       IdempotentGuard idempotentGuard,
                                                       NotificationReadClient readClient,
                                                       CallbackRateLimiter rateLimiter,
                                                       RestCallbackDeniedAuditWriter auditWriter) {
        return new FeishuCallbackService(verifier, replayGuard, idempotentGuard,
                readClient, auditWriter::writeDenied, rateLimiter);
    }
}
