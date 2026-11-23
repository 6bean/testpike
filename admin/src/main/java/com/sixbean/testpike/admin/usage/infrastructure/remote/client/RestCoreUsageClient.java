package com.sixbean.testpike.admin.usage.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.usage.api.dto.UsageCalibrationView;
import com.sixbean.testpike.admin.usage.api.dto.UsageSamplePageView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;


@Component
public class RestCoreUsageClient {

    private final RestClient rest;

    public RestCoreUsageClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    
    public List<UsageCalibrationView> calibration(int days) {
        return call(HttpMethod.GET, "/internal/llm-usage/calibration?days=" + days, null,
                new ParameterizedTypeReference<>() {
                });
    }

    /**
 * The optional sort pair forwards to the core column whitelist (single gate). */
    public UsageSamplePageView detail(String model, String chain, String keyword, int page, int size,
                                      String sortBy, String sortDir) {
        StringBuilder uri = new StringBuilder("/internal/llm-usage/samples?page=" + page + "&size=" + size);
        appendIfPresent(uri, "model", model);
        appendIfPresent(uri, "chain", chain);
        appendIfPresent(uri, "keyword", keyword);
        appendIfPresent(uri, "sortBy", sortBy);
        appendIfPresent(uri, "sortDir", sortDir);
        return call(HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<>() {
                });
    }

    private void appendIfPresent(StringBuilder uri, String key, String value) {
        if (value != null && !value.isBlank()) {
            uri.append('&').append(key).append('=')
                    .append(java.net.URLEncoder.encode(value.trim(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
