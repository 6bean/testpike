package com.sixbean.testpike.admin.plugin.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.plugin.api.dto.AppliedPluginView;
import com.sixbean.testpike.admin.plugin.api.dto.AppliedProjectView;
import com.sixbean.testpike.admin.plugin.api.dto.PluginApplyInput;
import com.sixbean.testpike.admin.plugin.api.dto.PluginApplyView;
import com.sixbean.testpike.admin.plugin.api.dto.PluginEnableInput;
import com.sixbean.testpike.admin.plugin.api.dto.PluginUpdateInput;
import com.sixbean.testpike.admin.plugin.api.dto.PluginView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;


@Component
public class RestCorePluginClient implements CorePluginClient {

    private final RestClient rest;

    public RestCorePluginClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public List<PluginView> list(String industry, Boolean enabled) {
        StringBuilder uri = new StringBuilder("/internal/plugins?");
        if (industry != null && !industry.isBlank()) {
            uri.append("industry=").append(URLEncoder.encode(industry, StandardCharsets.UTF_8));
        }
        if (enabled != null) {
            if (uri.charAt(uri.length() - 1) != '?') {
                uri.append('&');
            }
            uri.append("enabled=").append(enabled);
        }
        String url = uri.toString();
        return call(HttpMethod.GET, url, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public PluginView register(long operatorId, String content) {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/plugins",
                Map.of("content", content == null ? "" : content),
                new ParameterizedTypeReference<Response<PluginView>>() {
                }, operatorId);
    }

    @Override
    public PluginView setEnabled(long operatorId, long id, PluginEnableInput input) {
        return RestClientInvoker.call(rest, HttpMethod.PUT, "/internal/plugins/" + id + "/enable",
                input, new ParameterizedTypeReference<>() {
                }, operatorId);
    }

    @Override
    public PluginApplyView apply(long operatorId, long projectId, long pluginId,
                                 PluginApplyInput input) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/projects/" + projectId + "/plugins/" + pluginId + "/apply",
                input, new ParameterizedTypeReference<>() {
                }, operatorId);
    }

    @Override
    public void uninstall(long operatorId, long projectId, long pluginId) {
        RestClientInvoker.call(rest, HttpMethod.DELETE,
                "/internal/projects/" + projectId + "/plugins/" + pluginId,
                null, new ParameterizedTypeReference<Response<Void>>() {
                }, operatorId);
    }

    @Override
    public List<AppliedPluginView> listApplied(long operatorId, long projectId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/projects/" + projectId + "/plugins",
                null, new ParameterizedTypeReference<Response<List<AppliedPluginView>>> () {
                }, operatorId);
    }

    @Override
    public PluginView detail(long operatorId, long id) {
        return RestClientInvoker.call(rest, HttpMethod.GET, "/internal/plugins/" + id,
                null, new ParameterizedTypeReference<Response<PluginView>>() {
                }, operatorId);
    }

    @Override
    public PluginView update(long operatorId, long id, PluginUpdateInput input) {
        return RestClientInvoker.call(rest, HttpMethod.PUT, "/internal/plugins/" + id,
                input, new ParameterizedTypeReference<Response<PluginView>>() {
                }, operatorId);
    }

    @Override
    public List<AppliedProjectView> appliedProjects(long operatorId, long id) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/plugins/" + id + "/applied-projects",
                null, new ParameterizedTypeReference<Response<List<AppliedProjectView>>>() {
                }, operatorId);
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
