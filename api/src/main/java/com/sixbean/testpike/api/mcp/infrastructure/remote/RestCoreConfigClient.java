package com.sixbean.testpike.api.mcp.infrastructure.remote;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;


@Component
public class RestCoreConfigClient {

    private static final Logger log = LoggerFactory.getLogger(RestCoreConfigClient.class);

    private static final ParameterizedTypeReference<Response<String>> RESP_STRING =
            new ParameterizedTypeReference<>() {
            };

 
    static final boolean DEFAULT_READONLY = false;
 
    static final int DEFAULT_RATE_LIMIT = 30;

    private final RestClient rest;

    public RestCoreConfigClient(
            @Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    
    public String value(String key, String defaultValue) {
        String uri = "/internal/configs/value?key=" + encode(key)
                + "&defaultValue=" + encode(defaultValue == null ? "" : defaultValue);
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null, RESP_STRING, null);
    }

    
    public BooleanSupplier readonlyFlag() {
        return () -> {
            try {
                return Boolean.parseBoolean(value("mcp.readonly", "false"));
            } catch (RuntimeException ex) {
                log.warn("mcp.readonly 读取失败，按默认非只读处理（fail-closed 语义=不扩大写禁令面）：{}",
                        ex.getMessage());
                return DEFAULT_READONLY;
            }
        };
    }

    
    public IntSupplier rateLimitPerMinute() {
        return () -> {
            try {
                int parsed = Integer.parseInt(value("mcp.rate-limit/min", "30").trim());
                return parsed > 0 ? parsed : DEFAULT_RATE_LIMIT;
            } catch (RuntimeException ex) {
                log.warn("mcp.rate-limit/min 读取失败或非法，回退默认 {}：{}",
                        DEFAULT_RATE_LIMIT, ex.getMessage());
                return DEFAULT_RATE_LIMIT;
            }
        };
    }

 /** Fallback for mcp.quota.calls-per-token-day (kept equal to the V542 seed default). */
    static final int DEFAULT_DAILY_QUOTA = 2000;

    /**
     * Live-read supplier for mcp.quota.calls-per-token-day (daily per-token call
     * quota). Unreadable or non-positive values fall back to the seed default with
     * a warn; the value is re-read on every call so edits take effect immediately.
     */
    public IntSupplier dailyQuotaCallsPerTokenDay() {
        return () -> {
            try {
                int parsed = Integer.parseInt(value("mcp.quota.calls-per-token-day", "2000").trim());
                return parsed > 0 ? parsed : DEFAULT_DAILY_QUOTA;
            } catch (RuntimeException ex) {
                log.warn("mcp.quota.calls-per-token-day 读取失败或非法，回退默认 {}：{}",
                        DEFAULT_DAILY_QUOTA, ex.getMessage());
                return DEFAULT_DAILY_QUOTA;
            }
        };
    }

    /**
     * Live-read supplier for mcp.doc.max-bytes (the document-channel byte
     * ceiling). Unreadable or non-positive values fall back to the shipped
     * default with a warn; re-read on every call so edits take effect at once.
     */
    public java.util.function.LongSupplier docMaxBytes() {
        return () -> {
            try {
                long parsed = Long.parseLong(
                        value(RestMcpToolGateway.DOC_MAX_BYTES_KEY,
                                String.valueOf(RestMcpToolGateway.DEFAULT_DOC_MAX_BYTES)).trim());
                return parsed > 0 ? parsed : RestMcpToolGateway.DEFAULT_DOC_MAX_BYTES;
            } catch (RuntimeException ex) {
                log.warn("%s read failed or illegal, falling back to %d",
                        RestMcpToolGateway.DOC_MAX_BYTES_KEY,
                        RestMcpToolGateway.DEFAULT_DOC_MAX_BYTES, ex);
                return RestMcpToolGateway.DEFAULT_DOC_MAX_BYTES;
            }
        };
    }

    /**
     * Live-read supplier for mcp.gen.max-requirements (the per-call requirement
     * count ceiling of the generation-trigger tool). Same shape as docMaxBytes:
     * unreadable or non-positive values fall back to the shipped default with a
     * warn, and the value is re-read on every call so a settings edit takes
     * effect without a restart.
     */
    public java.util.function.LongSupplier genMaxRequirements() {
        return () -> {
            try {
                long parsed = Long.parseLong(
                        value(RestMcpToolGateway.GEN_MAX_REQUIREMENTS_KEY,
                                String.valueOf(RestMcpToolGateway.DEFAULT_GEN_MAX_REQUIREMENTS)).trim());
                return parsed > 0 ? parsed : RestMcpToolGateway.DEFAULT_GEN_MAX_REQUIREMENTS;
            } catch (RuntimeException ex) {
                log.warn("%s read failed or illegal, falling back to %d",
                        RestMcpToolGateway.GEN_MAX_REQUIREMENTS_KEY,
                        RestMcpToolGateway.DEFAULT_GEN_MAX_REQUIREMENTS, ex);
                return RestMcpToolGateway.DEFAULT_GEN_MAX_REQUIREMENTS;
            }
        };
    }

    
    public LongSupplier secondsSupplier(String key, long fallback) {
        return () -> {
            try {
                long parsed = Long.parseLong(value(key, String.valueOf(fallback)).trim());
                if (parsed > 0) {
                    return parsed;
                }
                log.warn("治理键 {} 值非正，回退默认 {}：{}", key, fallback, parsed);
                return fallback;
            } catch (RuntimeException ex) {
                log.warn("治理键 {} 读取失败或非法，回退默认 {}：{}", key, fallback, ex.getMessage());
                return fallback;
            }
        };
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
