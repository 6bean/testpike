package com.sixbean.testpike.admin.suites.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.suites.api.dto.SuiteCreateRequest;
import com.sixbean.testpike.admin.suites.api.dto.SuiteRunResultView;
import com.sixbean.testpike.admin.suites.api.dto.SuiteUpdateRequest;
import com.sixbean.testpike.admin.suites.api.dto.SuiteView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;


@Component
public class RestCoreSuiteClient implements CoreSuiteClient {

    private final RestClient rest;

    public RestCoreSuiteClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public SuiteView create(SuiteCreateRequest request) {
        return call(HttpMethod.POST, "/internal/suites", request, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public SuiteView update(long id, SuiteUpdateRequest request) {
        return call(HttpMethod.PUT, "/internal/suites/" + id, request, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public SuiteView get(long id) {
        return call(HttpMethod.GET, "/internal/suites/" + id, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public PageView<SuiteView> list(long projectId, String keyword, Integer status, int page, int size,
                                    String sortBy, String sortDir) {
        String url = "/internal/suites?projectId=" + projectId + "&page=" + page + "&size=" + size;
        if (keyword != null && !keyword.isBlank()) {
            url += "&keyword=" + keyword;
        }
        if (status != null) {
            url += "&status=" + status;
        }
        if (sortBy != null && !sortBy.isBlank()) {
            url += "&sortBy=" + java.net.URLEncoder.encode(sortBy, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (sortDir != null && !sortDir.isBlank()) {
            url += "&sortDir=" + sortDir;
        }
        return call(HttpMethod.GET, url, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public void delete(long id) {
        call(HttpMethod.DELETE, "/internal/suites/" + id, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public SuiteRunResultView run(long id) {
        return call(HttpMethod.POST, "/internal/suites/" + id + "/run", null,
                new ParameterizedTypeReference<>() {
                });
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
