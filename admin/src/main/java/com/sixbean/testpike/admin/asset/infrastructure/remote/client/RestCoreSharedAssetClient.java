package com.sixbean.testpike.admin.asset.infrastructure.remote.client;

import com.sixbean.testpike.admin.asset.api.dto.AssetPinForm;
import com.sixbean.testpike.admin.asset.api.dto.AssetPublishForm;
import com.sixbean.testpike.admin.asset.api.dto.AssetReferenceView;
import com.sixbean.testpike.admin.asset.api.dto.AssetVersionTimelineView;
import com.sixbean.testpike.admin.asset.api.dto.SharedAssetView;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RestClient implementation of the shared-asset bridge (core
 * /internal/shared-assets doors, tail).
 * <p>Asset kind/name may carry non-ASCII text: RestClientInvoker.call does not
 * re-encode the URI, so query-segment encoding is this class's job
 * (URLEncoder), mirroring RestCoreApiInventoryClient.endpoints. The pin body is
 * rebuilt without the acting projectId because the core request record keeps
 * the caller id positional in the query (a body projectId must not smuggle a
 * second, conflicting caller). X-Operator-Id travels via the shared invoker
 * for the core audit trail.
 */
@Component
public class RestCoreSharedAssetClient implements CoreSharedAssetClient {

    private static final String BASE = "/internal/shared-assets";

    private final RestClient rest;

    public RestCoreSharedAssetClient(
            @Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public SharedAssetView publish(long callerProjectId, AssetPublishForm form) {
        return RestClientInvoker.call(rest, org.springframework.http.HttpMethod.POST,
                BASE + "?callerProjectId=" + callerProjectId, form,
                new ParameterizedTypeReference<Response<SharedAssetView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public SharedAssetView get(long id) {
        return RestClientInvoker.call(rest, org.springframework.http.HttpMethod.GET,
                BASE + "/" + id, null,
                new ParameterizedTypeReference<Response<SharedAssetView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public PageView<SharedAssetView> list(String kind, int page, int size, String sortBy, String sortDir) {
        String kindQuery = kind == null || kind.isBlank()
                ? "" : "&kind=" + URLEncoder.encode(kind, StandardCharsets.UTF_8);
        String sortQuery = sortBy == null || sortBy.isBlank()
                ? "" : "&sortBy=" + URLEncoder.encode(sortBy, StandardCharsets.UTF_8)
                        + (sortDir == null || sortDir.isBlank() ? "" : "&sortDir=" + sortDir);
        return RestClientInvoker.call(rest, org.springframework.http.HttpMethod.GET,
                BASE + "?page=" + page + "&size=" + size + kindQuery + sortQuery, null,
                new ParameterizedTypeReference<Response<PageView<SharedAssetView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public void withdraw(long callerProjectId, long assetId, boolean force) {
        RestClientInvoker.call(rest, org.springframework.http.HttpMethod.POST,
                BASE + "/" + assetId + "/withdraw?callerProjectId=" + callerProjectId
                        + "&force=" + force, null,
                new ParameterizedTypeReference<Response<Void>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public AssetReferenceView pin(long callerProjectId, AssetPinForm form) {
        Map<String, Object> coreBody = new LinkedHashMap<>();
        coreBody.put("assetRowId", form.assetRowId());
        coreBody.put("referrerKind", form.referrerKind());
        coreBody.put("referrerId", form.referrerId());
        return RestClientInvoker.call(rest, org.springframework.http.HttpMethod.POST,
                BASE + "/references?callerProjectId=" + callerProjectId, coreBody,
                new ParameterizedTypeReference<Response<AssetReferenceView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public PageView<AssetReferenceView> referencesToChain(long chainProjectId, String kind,
                                                          String name, int page, int size) {
        return RestClientInvoker.call(rest, org.springframework.http.HttpMethod.GET,
                BASE + "/references/to-chain?chainProjectId=" + chainProjectId
                        + "&kind=" + URLEncoder.encode(kind, StandardCharsets.UTF_8)
                        + "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8)
                        + "&page=" + page + "&size=" + size, null,
                new ParameterizedTypeReference<Response<PageView<AssetReferenceView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public PageView<AssetReferenceView> referencesByConsumer(long consumerProjectId,
                                                             int page, int size) {
        return RestClientInvoker.call(rest, org.springframework.http.HttpMethod.GET,
                BASE + "/references/by-consumer?consumerProjectId=" + consumerProjectId
                        + "&page=" + page + "&size=" + size, null,
                new ParameterizedTypeReference<Response<PageView<AssetReferenceView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public List<AssetVersionTimelineView> chainVersions(long chainProjectId, String kind,
                                                        String name) {
        return RestClientInvoker.call(rest, org.springframework.http.HttpMethod.GET,
                BASE + "/references/chain-versions?chainProjectId=" + chainProjectId
                        + "&kind=" + URLEncoder.encode(kind, StandardCharsets.UTF_8)
                        + "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8), null,
                new ParameterizedTypeReference<Response<List<AssetVersionTimelineView>>>() {
                }, UserContext.currentUserId());
    }
}
