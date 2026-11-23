package com.sixbean.testpike.api.notify.infrastructure.remote.client;

import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;


@Component
public class RestCallbackDeniedAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(RestCallbackDeniedAuditWriter.class);

    private final RestClient rest;

    public RestCallbackDeniedAuditWriter(
            @Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    
    public void writeDenied(String reason) {
        Map<String, Object> input = Map.of(
                "objectType", "notification",
                "objectId", "feishu-callback",
                "action", "CALLBACK_DENIED",
                "before", Map.of("reason", reason),
                "after", Map.of());
        try {
            RestClientInvoker.call(rest, HttpMethod.POST, "/internal/audit-logs", input,
                    new ParameterizedTypeReference<Response<Void>>() {
                    }, null);
        } catch (RuntimeException ex) {
            log.warn("回调拒绝审计写入失败（不阻断拒绝判定）：{}", ex.getMessage());
        }
    }
}
