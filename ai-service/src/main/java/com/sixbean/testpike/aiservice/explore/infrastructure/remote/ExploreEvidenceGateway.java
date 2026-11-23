package com.sixbean.testpike.aiservice.explore.infrastructure.remote;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;


@Component
public class ExploreEvidenceGateway {

    private final CoreGateway coreGateway;
    private final RestClient rest;

    public ExploreEvidenceGateway(CoreGateway coreGateway, RestClient.Builder restClientBuilder) {
        this.coreGateway = coreGateway;
        this.rest = restClientBuilder.build();
    }

    
    public String uploadEvidence(long sessionId, String kind, String fileName,
                                 byte[] bytes, String contentType) {
        ExploreGrantBrief grant = coreGateway.grantExploreEvidence(sessionId, kind, fileName);
        rest.put()
                .uri(URI.create(grant.uploadUrl()))
                .contentType(MediaType.parseMediaType(
                        contentType == null || contentType.isBlank()
                                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType))
                .body(bytes)
                .retrieve()
                .toBodilessEntity();
        return grant.objectKey();
    }
}
