package com.sixbean.testpike.admin.imports.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.imports.api.dto.ImportResultView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;


@Component
public class RestCoreImportClient implements CoreImportClient {

    private final RestClient rest;

    public RestCoreImportClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl, java.time.Duration.ofSeconds(120));
    }

    @Override
    public ImportResultView importCases(long projectId, Integer targetStatus, byte[] file, String fileName) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", file, MediaType.APPLICATION_OCTET_STREAM).filename(fileName);
        body.part("projectId", projectId);
        if (targetStatus != null) {
            body.part("targetStatus", targetStatus);
        }
        return callMultipart("/internal/import/cases", body);
    }

    @Override
    public ImportResultView retryFailedCases(long projectId, Integer targetStatus, List<Integer> retryRows,
                                             byte[] file, String fileName) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", file, MediaType.APPLICATION_OCTET_STREAM).filename(fileName);
        body.part("projectId", projectId);
        if (targetStatus != null) {
            body.part("targetStatus", targetStatus);
        }
        retryRows.forEach(row -> body.part("retryRows", row));
        return callMultipart("/internal/import/cases/retry", body);
    }

    @Override
    public ImportResultView importRequirements(long projectId, byte[] file, String fileName) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", file, MediaType.APPLICATION_OCTET_STREAM).filename(fileName);
        body.part("projectId", projectId);
        return callMultipart("/internal/import/requirements", body);
    }

    @Override
    public byte[] caseTemplate() {
        return RestClientInvoker.call(rest, org.springframework.http.HttpMethod.GET,
                "/internal/import/templates/cases", null, new ParameterizedTypeReference<>() {
                }, UserContext.currentUserId());
    }

    @Override
    public byte[] requirementTemplate() {
        return RestClientInvoker.call(rest, org.springframework.http.HttpMethod.GET,
                "/internal/import/templates/requirements", null, new ParameterizedTypeReference<>() {
                }, UserContext.currentUserId());
    }

    private ImportResultView callMultipart(String uri, MultipartBodyBuilder body) {
        return RestClientInvoker.callMultipart(rest, uri, body, new ParameterizedTypeReference<>() {
        }, UserContext.currentUserId());
    }
}
