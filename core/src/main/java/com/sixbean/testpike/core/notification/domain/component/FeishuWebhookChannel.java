package com.sixbean.testpike.core.notification.domain.component;

import com.sixbean.testpike.common.report.PassRateSql;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.mcp.domain.component.McpEndpointPolicy;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@code X-Lark-Request-Timestamp} + {@code X-Lark-Request-Sign}
 */
@Component
public class FeishuWebhookChannel {

    private static final Logger log = LoggerFactory.getLogger(FeishuWebhookChannel.class);

    public static final String KEY_ENABLED = "notify.feishu.enabled";
    public static final String KEY_WEBHOOK = "notify.feishu.webhook";
    public static final String KEY_SECRET = "notify.feishu.secret";
    public static final String KEY_BASE_URL = "notify.web.base-url";
    
    public static final String KEY_CARD_ENABLED = "notify.feishu.card.enabled";
    
    public static final String KEY_CALLBACK_ENCRYPT = "notify.feishu.callback.encrypt-key";

    
    private static final List<String> BATCH_EVENT_FAMILIES = List.of("BATCH_FAILED", "BATCH_FINISHED");

    private final ConfigCacheHolder configCacheHolder;
    private final ConfigCipher configCipher;
    private final McpEndpointPolicy mcpEndpointPolicy;
    private final RetryingWebhookSender retryingWebhookSender;
    private final JdbcTemplate jdbcTemplate;
    
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "feishu-sender");
        t.setDaemon(true);
        return t;
    });

    public FeishuWebhookChannel(ConfigCacheHolder configCacheHolder,
                                ConfigCipher configCipher,
                                McpEndpointPolicy mcpEndpointPolicy,
                                RetryingWebhookSender retryingWebhookSender,
                                JdbcTemplate jdbcTemplate) {
        this.configCacheHolder = configCacheHolder;
        this.configCipher = configCipher;
        this.mcpEndpointPolicy = mcpEndpointPolicy;
        this.retryingWebhookSender = retryingWebhookSender;
        this.jdbcTemplate = jdbcTemplate;
    }

    
    public boolean globalEnabled() {
        return WebhookChannel.isEnabled(configCacheHolder.get(KEY_ENABLED));
    }

    
    public boolean sendBatchNotice(long batchId) {
        if (!globalEnabled()) {
            log.debug("飞书渠道未启用（{}），批次 {} 跳过外呼", KEY_ENABLED, batchId);
            return false;
        }
        Map<String, Object> batch = jdbcTemplate.queryForMap(
                "SELECT b.name, p.name AS project_name FROM tp_exec_batch b"
                        + " JOIN tp_project p ON p.id = b.project_id WHERE b.id = ?", batchId);
        Map<String, Object> rate = jdbcTemplate.queryForMap(
                "SELECT " + PassRateSql.RATE_EXPR + " AS pass_rate FROM tp_exec_task t"
                        + " WHERE t.batch_id = ?", batchId);
        BigDecimal passRate = rate.get("pass_rate") == null
                ? null : new BigDecimal(String.valueOf(rate.get("pass_rate")));

        String batchLabel = batch.get("name") == null ? "#" + batchId : String.valueOf(batch.get("name"));
        StringBuilder text = new StringBuilder("批次「").append(batchLabel).append("」执行完成");
        if (batch.get("project_name") != null) {
            text.append("（项目：").append(batch.get("project_name")).append("）");
        }
        text.append("，通过率 ").append(formatRate(passRate));
        String detailUrl = detailUrl(batchId);
        if (detailUrl != null) {
            text.append("，明细：").append(detailUrl);
        }
        return postMessage("批次通知", text.toString(), buildPostPayload("批次通知", text.toString()));
    }

    
    public void sendAsync(NotificationCreateInput input, long notificationId) {
        if (isBatchFamily(input.eventType())) {
            return;
        }
        executor.submit(() -> {
            try {
                if (!globalEnabled()) {
                    return;
                }
                String title = input.title();
                String text = title + "\n" + input.content();
                if (cardEnabled() && FeishuInteractiveCardBuilder.isCardEvent(input.eventType())) {
                    try {
                        Map<String, Object> card = FeishuInteractiveCardBuilder.build(input, notificationId,
                                displayName(input.userId()), configCacheHolder.get(KEY_BASE_URL),
                                readConfigValue(KEY_CALLBACK_ENCRYPT));
                        postMessage(title, text, card);
                        return;
                    } catch (RuntimeException e) {
                        log.warn("飞书交互卡片构建失败降级文本卡: eventType={} err={}",
                                input.eventType(), e.getMessage());
                    }
                }
                postMessage(title, text, buildPostPayload(title, text));
            } catch (Exception e) {
                log.error("飞书异步发送异常已兜底: eventType={} err={}", input.eventType(), e.getMessage());
            }
        });
    }

    
    boolean cardEnabled() {
        return WebhookChannel.isEnabled(configCacheHolder.get(KEY_CARD_ENABLED));
    }

    
    private String displayName(long userId) {
        return jdbcTemplate.query(
                "SELECT display_name FROM tp_user WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, userId);
    }

    
    public static boolean isBatchFamily(String eventType) {
        return eventType != null && BATCH_EVENT_FAMILIES.contains(eventType);
    }

    
    private boolean postMessage(String title, String logText, Map<String, Object> payload) {
        String url = configCacheHolder.get(KEY_WEBHOOK);
        if (url == null || url.isBlank()) {
            log.warn("飞书渠道已启用但未配置 webhook 地址，通知「{}」降级跳过外呼", title);
            return false;
        }
        try {
            mcpEndpointPolicy.validate(url, "webhook 地址", "飞书 ");
            if (!FeishuWebhookUrlRule.isHttpsOrLoopback(url)) {
                log.warn("飞书 webhook 地址「{}」不满足出站面规则（{}），拒发", url, FeishuWebhookUrlRule.ruleText());
                return false;
            }
        } catch (RuntimeException e) {
            log.warn("飞书 webhook 地址读侧二次校验拒发: {}", e.getMessage());
            return false;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        String secret = readSecret();
        if (secret != null && !secret.isBlank()) {
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            headers.put("X-Lark-Request-Timestamp", timestamp);
            headers.put("X-Lark-Request-Sign", sign(timestamp, secret));
        }
        return retryingWebhookSender.postWithRetry("飞书渠道", url, payload, headers);
    }

    
    private static Map<String, Object> buildPostPayload(String title, String text) {
        Map<String, Object> zhContent = new LinkedHashMap<>();
        zhContent.put("title", title);
        zhContent.put("content", List.of(List.of(Map.of("tag", "text", "text", text))));
        Map<String, Object> post = new LinkedHashMap<>();
        post.put("zh_cn", zhContent);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msg_type", "post");
        payload.put("content", post);
        return payload;
    }

    
    private String detailUrl(long batchId) {
        String base = configCacheHolder.get(KEY_BASE_URL);
        if (base == null || base.isBlank()) {
            return null;
        }
        String trimmed = base.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "/batches/" + batchId;
    }

    
    private static String formatRate(BigDecimal passRate) {
        if (passRate == null) {
            return "暂无";
        }
        return passRate.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP) + "%";
    }

    
    private String readSecret() {
        return readConfigValue(KEY_SECRET);
    }

    
    private String readConfigValue(String key) {
        String[] holder = jdbcTemplate.query(
                "SELECT config_value, encrypt_flag FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? new String[]{rs.getString(1), String.valueOf(rs.getInt(2))} : null,
                key);
        if (holder == null || holder[0] == null || holder[0].isBlank()) {
            return null;
        }
        String stored = holder[0].trim();
        if ("1".equals(holder[1]) && configCipher.isEnabled()) {
            try {
                return configCipher.decrypt(stored);
            } catch (RuntimeException e) {
                return stored;
            }
        }
        return stored;
    }

    
    private static String sign(String timestamp, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error("飞书签名计算失败，按无签名模式发送: {}", e.getMessage());
            return "";
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
