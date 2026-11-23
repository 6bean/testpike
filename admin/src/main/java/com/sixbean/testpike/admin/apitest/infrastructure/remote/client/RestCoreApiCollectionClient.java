package com.sixbean.testpike.admin.apitest.infrastructure.remote.client;

import com.sixbean.testpike.admin.apitest.api.dto.ApiCollectionView;
import com.sixbean.testpike.admin.apitest.api.dto.CollectionSaveRequest;
import com.sixbean.testpike.admin.apitest.api.dto.CollectionUpdateRequest;
import com.sixbean.testpike.admin.apitest.api.dto.FlowPromotionResultDto;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;


@Component
public class RestCoreApiCollectionClient implements CoreApiCollectionClient {

    private static final String BASE = "/internal/apitest/collections";

    private final RestClient rest;

    public RestCoreApiCollectionClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public ApiCollectionView createCollection(CollectionSaveRequest request) {
        return call(HttpMethod.POST, BASE, request, new ParameterizedTypeReference<Response<ApiCollectionView>>() {
        });
    }

    @Override
    public ApiCollectionView renameCollection(long id, CollectionUpdateRequest request) {
        return call(HttpMethod.PUT, BASE + "/" + id, request,
                new ParameterizedTypeReference<Response<ApiCollectionView>>() {
                });
    }

    @Override
    public void deleteCollection(long id) {
        call(HttpMethod.DELETE, BASE + "/" + id, null, new ParameterizedTypeReference<Response<Void>>() {
        });
    }

    @Override
    public List<ApiCollectionView> listCollections(long projectId, long programId) {
        // The lens parameter is only appended when a lens is actually picked
        // (RestCoreBatchClient posture — an absent lens stays byte-identical legacy).
        return call(HttpMethod.GET, BASE + "?projectId=" + projectId
                        + (programId > 0 ? "&programId=" + programId : ""), null,
                new ParameterizedTypeReference<Response<List<ApiCollectionView>>>() {
                });
    }

    @Override
    public ApiCollectionView getCollection(long id) {
        return call(HttpMethod.GET, BASE + "/" + id, null,
                new ParameterizedTypeReference<Response<ApiCollectionView>>() {
                });
    }

    @Override
    public FlowPromotionResultDto promoteFlow(long id) {
        return call(HttpMethod.POST, BASE + "/" + id + "/promote-flow", null,
                new ParameterizedTypeReference<Response<FlowPromotionResultDto>>() {
                });
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
