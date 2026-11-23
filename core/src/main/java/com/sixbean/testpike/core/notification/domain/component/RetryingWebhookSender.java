package com.sixbean.testpike.core.notification.domain.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;


@Component
public class RetryingWebhookSender {

    private static final Logger log = LoggerFactory.getLogger(RetryingWebhookSender.class);

 
    private static final int MAX_ATTEMPTS = 4;
    private static final long[] RETRY_INTERVAL_MS = {500, 1000, 2000};

    private final RestClient restClient;

    public RetryingWebhookSender() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    
    public boolean postWithRetry(String channelLabel, String url, Object payload, Map<String, String> headers) {
        Exception lastError = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(RETRY_INTERVAL_MS[Math.min(attempt - 1, RETRY_INTERVAL_MS.length - 1)]);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            try {
                restClient.post().uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .headers(httpHeaders -> headers.forEach(httpHeaders::set))
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
                return true;
            } catch (Exception e) {
                lastError = e;
                log.warn("{} 发送失败（第 {} 次，共 {} 次尝试）: url={} err={}",
                        channelLabel, attempt + 1, MAX_ATTEMPTS, url, e.getMessage());
            }
        }
        log.error("{} 发送失败（重试 3 次后放弃）: url={} err={}",
                channelLabel, url, lastError == null ? "unknown" : lastError.getMessage());
        return false;
    }
}
