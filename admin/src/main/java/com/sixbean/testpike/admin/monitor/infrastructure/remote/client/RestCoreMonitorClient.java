package com.sixbean.testpike.admin.monitor.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.monitor.api.dto.MonitorCheckLogView;
import com.sixbean.testpike.admin.monitor.api.dto.MonitorEpisodeView;
import com.sixbean.testpike.admin.monitor.api.dto.MonitorLinkedAssetsView;
import com.sixbean.testpike.admin.monitor.api.dto.MonitorTargetView;
import com.sixbean.testpike.admin.monitor.api.dto.SilenceRequest;
import com.sixbean.testpike.admin.monitor.api.dto.TargetSaveRequest;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;


@Component
public class RestCoreMonitorClient implements CoreMonitorClient {

    private static final String BASE = "/internal/monitor";

    private final RestClient rest;

    public RestCoreMonitorClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public long createTarget(TargetSaveRequest request) {
        Long id = call(HttpMethod.POST, BASE + "/targets?projectId=" + request.projectId(), request,
                new ParameterizedTypeReference<Response<Long>>() {
                });
        return id == null ? 0L : id;
    }

    @Override
    public void updateTarget(long id, TargetSaveRequest request) {
        call(HttpMethod.PUT, BASE + "/targets/" + id, request,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public MonitorTargetView findTarget(long id) {
        return call(HttpMethod.GET, BASE + "/targets/" + id, null,
                new ParameterizedTypeReference<Response<MonitorTargetView>>() {
                });
    }

    @Override
    public List<MonitorTargetView> listTargets(long projectId) {
        return call(HttpMethod.GET, BASE + "/targets?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<List<MonitorTargetView>>>() {
                });
    }

    @Override
    public com.sixbean.testpike.admin.monitor.api.dto.MonitorTargetPageView pageTargets(
            long projectId, int page, int pageSize, String sortBy, String sortDir,
            String status, String targetType, String keyword) {
        StringBuilder url = new StringBuilder(BASE)
                .append("/targets/page?projectId=").append(projectId)
                .append("&page=").append(page).append("&pageSize=").append(pageSize);
        com.sixbean.testpike.common.client.SortQueryPassThrough.append(url, sortBy, sortDir);
        appendNonEmptyQuery(url, "status", status);
        appendNonEmptyQuery(url, "targetType", targetType);
        appendNonEmptyQuery(url, "keyword", keyword);
        return call(HttpMethod.GET, url.toString(), null,
                new ParameterizedTypeReference<Response<
                        com.sixbean.testpike.admin.monitor.api.dto.MonitorTargetPageView>>() {
                });
    }

    /** Pass a wall filter only when present; blanks are absent at the core edge (URL-encoded, UTF-8). */
    private static void appendNonEmptyQuery(StringBuilder url, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        url.append('&').append(key).append('=')
                .append(java.net.URLEncoder.encode(value.trim(), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public void updateEnabled(long id, boolean enabled) {
        call(HttpMethod.PUT, BASE + "/targets/" + id + "/enabled?enabled=" + enabled, null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public void deleteTarget(long id) {
        call(HttpMethod.DELETE, BASE + "/targets/" + id, null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public void silence(long id, SilenceRequest request) {
        call(HttpMethod.POST, BASE + "/targets/" + id + "/silence", request,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public void clearSilence(long id) {
        call(HttpMethod.DELETE, BASE + "/targets/" + id + "/silence", null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public List<MonitorCheckLogView> checkLogs(long targetId, int limit) {
        return call(HttpMethod.GET, BASE + "/targets/" + targetId + "/check-logs?limit=" + limit, null,
                new ParameterizedTypeReference<Response<List<MonitorCheckLogView>>>() {
                });
    }

    @Override
    public MonitorEpisodeView openEpisode(long envId) {
        return call(HttpMethod.GET, BASE + "/environments/" + envId + "/open-episode", null,
                new ParameterizedTypeReference<Response<MonitorEpisodeView>>() {
                });
    }

    @Override
    public MonitorLinkedAssetsView linkedAssets(long envId) {
        return call(HttpMethod.GET, BASE + "/environments/" + envId + "/linked-assets", null,
                new ParameterizedTypeReference<Response<MonitorLinkedAssetsView>>() {
                });
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
