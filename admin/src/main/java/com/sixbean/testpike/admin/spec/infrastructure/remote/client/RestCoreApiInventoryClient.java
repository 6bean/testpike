package com.sixbean.testpike.admin.spec.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.spec.api.dto.InventoryCoverageSummary;
import com.sixbean.testpike.admin.spec.api.dto.InventoryEndpointView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * RestClient implementation of the contract-inventory bridge (core
 * /internal/api-inventory two doors).
 * <p>Contract names may contain non-ASCII characters: RestClientInvoker.call
 * does not re-encode the URI, so query-segment encoding is this class's job
 * (URLEncoder), mirroring RestCoreApiSpecClient.history. A missing/blank scope
 * fails as 400 at core (primitive parameter there) instead of unboxing inside
 * admin. X-Operator-Id forwarded by the shared invoker.
 */
@Component
public class RestCoreApiInventoryClient implements CoreApiInventoryClient {

    private final RestClient rest;

    public RestCoreApiInventoryClient(
            @Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public PageView<InventoryEndpointView> endpoints(long projectId, String specName,
                                                    int page, int pageSize,
                                                    boolean uncoveredOnly) {
        return RestClientInvoker.call(rest, org.springframework.http.HttpMethod.GET,
                "/internal/api-inventory/endpoints?projectId=" + projectId
                        + "&specName=" + URLEncoder.encode(specName, StandardCharsets.UTF_8)
                        + "&page=" + page + "&pageSize=" + pageSize
                        + "&uncoveredOnly=" + uncoveredOnly, null,
                new ParameterizedTypeReference<Response<PageView<InventoryEndpointView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public InventoryCoverageSummary coverage(long projectId, String specName) {
        return RestClientInvoker.call(rest, org.springframework.http.HttpMethod.GET,
                "/internal/api-inventory/coverage?projectId=" + projectId
                        + "&specName=" + URLEncoder.encode(specName, StandardCharsets.UTF_8),
                null,
                new ParameterizedTypeReference<Response<InventoryCoverageSummary>>() {
                }, UserContext.currentUserId());
    }
}
