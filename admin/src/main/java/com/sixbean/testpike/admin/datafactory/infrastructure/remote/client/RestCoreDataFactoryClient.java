package com.sixbean.testpike.admin.datafactory.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.datafactory.api.dto.DataFactoryCreateRequest;
import com.sixbean.testpike.admin.datafactory.api.dto.DataFactoryRecordView;
import com.sixbean.testpike.admin.datafactory.api.dto.DataFactoryRunResultView;
import com.sixbean.testpike.admin.datafactory.api.dto.DataFactoryRunView;
import com.sixbean.testpike.admin.datafactory.api.dto.DataFactoryUpdateRequest;
import com.sixbean.testpike.admin.datafactory.api.dto.DataPoolItemView;
import com.sixbean.testpike.admin.datafactory.api.dto.GenFunctionGroupView;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Optional;


@Component
public class RestCoreDataFactoryClient implements CoreDataFactoryClient {

    private final RestClient rest;

    public RestCoreDataFactoryClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public DataFactoryRecordView create(DataFactoryCreateRequest request) {
        return call(HttpMethod.POST, "/internal/data-factories", request,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public PageView<DataFactoryRecordView> list(long projectId, String keyword, Integer type,
                                                int page, int size, String sortBy, String sortDir) {
        String url = UriComponentsBuilder.fromPath("/internal/data-factories")
                .queryParam("projectId", projectId)
                .queryParam("page", page)
                .queryParam("size", size)
                .queryParamIfPresent("keyword", Optional.ofNullable(keyword).filter(s -> !s.isBlank()))
                .queryParamIfPresent("type", Optional.ofNullable(type))
                .queryParamIfPresent("sortBy", Optional.ofNullable(sortBy).filter(s -> !s.isBlank()))
                .queryParamIfPresent("sortDir", Optional.ofNullable(sortDir).filter(s -> !s.isBlank()))
                .build().encode().toUriString();
        return call(HttpMethod.GET, url, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public DataFactoryRecordView get(long id) {
        return call(HttpMethod.GET, "/internal/data-factories/" + id, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public void update(long id, DataFactoryUpdateRequest request) {
        call(HttpMethod.PUT, "/internal/data-factories/" + id, request,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public void delete(long id) {
        call(HttpMethod.DELETE, "/internal/data-factories/" + id, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public DataFactoryRunResultView run(long id) {
        return call(HttpMethod.POST, "/internal/data-factories/" + id + "/run", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public PageView<DataFactoryRunView> runs(long id, long projectId, int page, int size) {
        return call(HttpMethod.GET, "/internal/data-factories/" + id + "/runs?projectId=" + projectId
                + "&page=" + page + "&size=" + size, null, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<DataFactoryRunView> recentRuns(long id, long projectId, int limit) {
        return call(HttpMethod.GET, "/internal/data-factories/" + id + "/recent-runs?projectId="
                + projectId + "&limit=" + limit, null, new ParameterizedTypeReference<>() {
                });
    }


    @Override
    public List<GenFunctionGroupView> genkitCatalog() {
        return call(HttpMethod.GET, "/internal/data-factories/genkit-catalog", null,
                new ParameterizedTypeReference<>() {
                });
    }


    @Override
    public PageView<DataPoolItemView> listPoolItems(long projectId, Long envId, String type,
                                                    Integer status, int page, int size) {
        String url = "/internal/data-factories/pool-items?projectId=" + projectId
                + "&page=" + page + "&size=" + size;
        if (envId != null) {
            url += "&envId=" + envId;
        }
        if (type != null && !type.isBlank()) {
            url += "&type=" + type;
        }
        if (status != null) {
            url += "&status=" + status;
        }
        return call(HttpMethod.GET, url, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public void updatePoolItemStatus(long projectId, long id, int status) {
        call(HttpMethod.PUT, "/internal/data-factories/pool-items/" + id + "/status?projectId="
                + projectId + "&status=" + status, null, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public void deletePoolItem(long projectId, long id) {
        call(HttpMethod.DELETE, "/internal/data-factories/pool-items/" + id + "?projectId="
                + projectId, null, new ParameterizedTypeReference<>() {
                });
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
