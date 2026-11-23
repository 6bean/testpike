package com.sixbean.testpike.admin.sample.infrastructure.remote.client;

import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.sample.api.dto.TrainingSampleView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;


@Component
public class RestTrainingSampleCoreClient implements TrainingSampleCoreClient {

    private final RestClient rest;

    public RestTrainingSampleCoreClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public PageView<TrainingSampleView> listSamples(Long projectId, String sampleType,
                                                    int page, int size, String sortBy, String sortDir) {
        StringBuilder uri = new StringBuilder("/internal/training-samples?page=" + page
                + "&size=" + size);
        if (projectId != null) {
            uri.append("&projectId=").append(projectId);
        }
        if (sampleType != null) {
            uri.append("&sampleType=").append(
                    UriUtils.encodeQueryParam(sampleType, StandardCharsets.UTF_8));
        }
        if (sortBy != null && !sortBy.isBlank()) {
            uri.append("&sortBy=").append(UriUtils.encodeQueryParam(sortBy, StandardCharsets.UTF_8));
        }
        if (sortDir != null && !sortDir.isBlank()) {
            uri.append("&sortDir=").append(UriUtils.encodeQueryParam(sortDir, StandardCharsets.UTF_8));
        }
        return RestClientInvoker.call(rest, HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public void updateAdoption(long id, int adopted) {
        RestClientInvoker.call(rest, HttpMethod.PUT,
                "/internal/training-samples/" + id + "/adoption?adopted=" + adopted, null,
                new ParameterizedTypeReference<Response<Void>>() {
                }, null);
    }
}
