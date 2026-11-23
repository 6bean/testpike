package com.sixbean.testpike.core.notification.domain.component;

import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;


@Component
public class WebhookChannel {

    private static final Logger log = LoggerFactory.getLogger(WebhookChannel.class);

    public static final String KEY_ENABLED = "testpike.notify.webhook-enabled";
    public static final String KEY_URL = "testpike.notify.webhook-url";

 
    static boolean isEnabled(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private final ConfigCacheHolder configCacheHolder;
    private final RetryingWebhookSender retryingWebhookSender;
    
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "webhook-sender");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger pending = new AtomicInteger();

    public WebhookChannel(ConfigCacheHolder configCacheHolder,
                          RetryingWebhookSender retryingWebhookSender) {
        this.configCacheHolder = configCacheHolder;
        this.retryingWebhookSender = retryingWebhookSender;
    }

    
    public void sendAsync(NotificationCreateInput input) {
        pending.incrementAndGet();
        executor.submit(() -> {
            try {
                send(input);
            } catch (Exception e) {
                log.error("webhook 异步发送异常已兜底: eventType={} err={}", input.eventType(), e.getMessage());
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    
    int pendingCount() {
        return pending.get();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    
    public boolean send(NotificationCreateInput input) {
        if (!isEnabled(configCacheHolder.get(KEY_ENABLED))) {
            log.debug("webhook 渠道未启用（{}），跳过发送", KEY_ENABLED);
            return false;
        }
        String url = configCacheHolder.get(KEY_URL);
        if (url == null || url.isBlank()) {
            log.warn("webhook 渠道已启用但未配置 URL，通知 {} 降级为仅站内信", input.eventType());
            return false;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", input.eventType());
        payload.put("title", input.title());
        payload.put("content", input.content());
        payload.put("projectId", input.projectId());
        payload.put("userId", input.userId());

        return retryingWebhookSender.postWithRetry("webhook", url, payload, Map.of());
    }
}
