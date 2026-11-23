package com.sixbean.testpike.admin.linkage.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.common.linkage.LinkagePendingImpacts;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * RestClient implementation of the pending-impact ledger hop (same road as
 * RestCoreDocChangeAnalysisClient — invocation logic is reused from
 * RestClientInvoker, never re-written; the operator id is passed through
 * UserContext, not HttpServletRequest). core business error codes keep
 * their semantics across the hop.
 */
@Component
public class RestCoreLinkageImpactClient implements CoreLinkageImpactClient {

    private final RestClient rest;

    public RestCoreLinkageImpactClient(
            @Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public LinkagePendingImpacts pending(String upstreamType, long upstreamId) {
        String uri = UriComponentsBuilder.fromUriString("/internal/linkage-impacts/pending")
                .queryParam("upstreamType", upstreamType)
                .queryParam("upstreamId", upstreamId)
                .build().toUriString();
        return RestClientInvoker.call(rest, org.springframework.http.HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<Response<LinkagePendingImpacts>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public java.util.List<com.sixbean.testpike.common.linkage.LinkageDraftImpact> byDrafts(
            java.util.List<Long> draftIds) {
        if (draftIds == null || draftIds.isEmpty()) {
            return java.util.List.of();
        }
        String joined = draftIds.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        String uri = UriComponentsBuilder.fromUriString("/internal/linkage-impacts/by-drafts")
                .queryParam("ids", joined)
                .build().toUriString();
        return RestClientInvoker.call(rest, org.springframework.http.HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<
                        Response<java.util.List<com.sixbean.testpike.common.linkage.LinkageDraftImpact>>>() {
                }, UserContext.currentUserId());
    }
}
