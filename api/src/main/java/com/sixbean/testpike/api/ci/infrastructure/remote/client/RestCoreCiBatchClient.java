package com.sixbean.testpike.api.ci.infrastructure.remote.client;

import com.sixbean.testpike.api.ci.api.dto.CiBadgeView;
import com.sixbean.testpike.api.ci.api.dto.CiCreateFromTemplateInput;
import com.sixbean.testpike.api.ci.api.dto.CiEnvOverrideInput;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;


@Component
public class RestCoreCiBatchClient implements CoreCiBatchClient {

    private static final ParameterizedTypeReference<Response<CiBadgeView>> RESP_BADGE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Response<Map<String, Object>>> RESP_MAP =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Response<List<Map<String, Object>>>> RESP_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient rest;

    public RestCoreCiBatchClient(
            @Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public CiBadgeView badge(String badgeToken) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/batches/badge/" + badgeToken, null, RESP_BADGE, null);
    }

    @Override
    public CiBadgeView badgeByBatchId(long batchId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/batches/" + batchId + "/badge", null, RESP_BADGE, null);
    }

    @Override
    public Map<String, Object> verdict(long batchId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/batches/" + batchId + "/verdict", null, RESP_MAP, null);
    }

    @Override
    public Map<String, Object> get(long batchId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/batches/" + batchId, null, RESP_MAP, null);
    }

    @Override
    public List<Map<String, Object>> listCases(long batchId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/batches/" + batchId + "/cases", null, RESP_LIST, null);
    }

    @Override
    public List<Map<String, Object>> listTemplates(long projectId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/batches/templates?projectId=" + projectId, null, RESP_LIST, null);
    }

    @Override
    public Map<String, Object> createFromTemplate(CiCreateFromTemplateInput input, long operatorId) {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/batches/from-template",
                input, RESP_MAP, operatorId);
    }

    @Override
    public Map<String, Object> start(long batchId, CiEnvOverrideInput envOverrides, long operatorId) {
        Object body = envOverrides == null || !envOverrides.hasAny()
                ? null : Map.of("envOverrides", envOverrides);
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/batches/" + batchId + "/start", body, RESP_MAP, operatorId);
    }
}
