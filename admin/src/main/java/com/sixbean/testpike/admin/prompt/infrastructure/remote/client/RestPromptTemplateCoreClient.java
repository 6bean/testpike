package com.sixbean.testpike.admin.prompt.infrastructure.remote.client;

import com.sixbean.testpike.admin.prompt.api.dto.BehaviorRolloutView;
import com.sixbean.testpike.admin.prompt.api.dto.PromptTemplateFamilyView;
import com.sixbean.testpike.admin.prompt.api.dto.PromptTemplateSaveRequest;
import com.sixbean.testpike.admin.prompt.api.dto.PromptTemplateView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;


@Component
public class RestPromptTemplateCoreClient implements PromptTemplateCoreClient {

    private final RestClient rest;

    public RestPromptTemplateCoreClient(
            @Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public PromptTemplateView save(PromptTemplateSaveRequest request) {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/prompt-templates",
                request, new ParameterizedTypeReference<>() {
                }, request.createdBy());
    }

    @Override
    public PromptTemplateView rollback(long sourceId, long operatorId) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/prompt-templates/" + sourceId + "/rollback?operatorId=" + operatorId,
                null, new ParameterizedTypeReference<>() {
                }, operatorId);
    }

    @Override
    public PromptTemplateView detail(long id) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/prompt-templates/" + id, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public List<PromptTemplateView> listVersions(String name, String domain) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/prompt-templates/versions?name=" + enc(name) + "&domain=" + enc(domain),
                null, new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public List<PromptTemplateFamilyView> listFamilies(String domain) {
        // null domain = every family (the query parameter is simply absent); a provided
        // filter is encoded through the shared enc single point like every other leg
        String path = domain == null
                ? "/internal/prompt-templates/families"
                : "/internal/prompt-templates/families?domain=" + enc(domain);
        return RestClientInvoker.call(rest, HttpMethod.GET, path, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public PromptTemplateView latest(String name, String domain) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/prompt-templates/latest?name=" + enc(name) + "&domain=" + enc(domain),
                null, new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public PromptTemplateView latestByStatus(String name, String domain, int status) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/prompt-templates/latest-by-status?name=" + enc(name)
                        + "&domain=" + enc(domain) + "&status=" + status,
                null, new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public PromptTemplateView promote(long id) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/prompt-templates/" + id + "/promote", null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public BehaviorRolloutView rolloutAdvisory(String domain, String name) {
        // advisory-grade relay: the comparison and the rollback suggestion are
        // judged in core alone; domain/name are user input, encoded through
        // the shared enc single point like every other query leg
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/behavior/rollout-advisory?domain=" + enc(domain) + "&name=" + enc(name),
                null, new ParameterizedTypeReference<>() {
                }, null);
    }

    
    private static String enc(String value) {
        return UriUtils.encodeQueryParam(value, StandardCharsets.UTF_8);
    }
}
