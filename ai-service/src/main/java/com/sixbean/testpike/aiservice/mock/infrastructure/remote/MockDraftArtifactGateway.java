package com.sixbean.testpike.aiservice.mock.infrastructure.remote;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;

/**
 * Draft-bundle upload gateway ; ExploreEvidenceGateway
 * precedent — core signs a project-routed presigned PUT, this side pushes raw
 * bytes, zero s3 dependency so the module boundary holds).
 * <p>Why an artifact instead of a column write: the drafted bundle is far
 * larger than the 300-char preview-reference column, so only the object key
 * ever travels back to core — the human-tick confirmation page reads the
 * bundle itself from storage.
 */
@Component
public class MockDraftArtifactGateway {

    /** Canonical file name of one drafting bundle (core sanitizes it into the key). */
    public static final String DRAFT_FILE_NAME = "draft.json";

    private final CoreGateway coreGateway;
    private final RestClient rest;

    public MockDraftArtifactGateway(CoreGateway coreGateway, RestClient.Builder restClientBuilder) {
        this.coreGateway = coreGateway;
        this.rest = restClientBuilder.build();
    }

    /**
     * Request the grant for one job, PUT the raw bundle bytes to the presigned
     * url, return the canonical object key for the preview write-back.
     *
     * @param jobId drafting job whose bundle is uploaded (existence guarded core-side)
     * @param bundleBytes draft bundle as UTF-8 JSON bytes (passed through unmodified)
     * @return object key to store as tp_mock_ai_job.result_preview_ref
     */
    public String uploadDraftBundle(long jobId, String fileName, byte[] bundleBytes) {
        MockDraftArtifactGrantBrief grant =
                coreGateway.grantMockDraftArtifact(jobId, fileName);
        rest.put()
                .uri(URI.create(grant.uploadUrl()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(bundleBytes == null ? new byte[0] : bundleBytes)
                .retrieve()
                .toBodilessEntity();
        return grant.objectKey();
    }
}
