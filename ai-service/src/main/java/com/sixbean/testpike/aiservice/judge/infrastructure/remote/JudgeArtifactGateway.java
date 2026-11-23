package com.sixbean.testpike.aiservice.judge.infrastructure.remote;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;


@Component
public class JudgeArtifactGateway {

    private final JudgeCoreGateway judgeGateway;
    private final RestClient rest;

    public JudgeArtifactGateway(JudgeCoreGateway judgeGateway, RestClient.Builder restClientBuilder) {
        this.judgeGateway = judgeGateway;
        this.rest = restClientBuilder.build();
    }

    
    public String upload(long runId, long sampleId, byte[] payload) {
        JudgeArtifactGrantBrief grant = judgeGateway.artifactGrant(runId, sampleId);
        rest.put()
                .uri(URI.create(grant.uploadUrl()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
        return grant.objectKey();
    }
}
