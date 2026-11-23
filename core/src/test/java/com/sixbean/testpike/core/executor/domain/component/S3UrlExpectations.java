package com.sixbean.testpike.core.executor.domain.component;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single-source URL expectation for presigned-URL assertions in artifact storage tests.
 * Resolves endpoint and bucket through the same Spring property binding used by
 * {@link S3StorageProvider}, so install-time customization (per-chain slot buckets,
 * custom ports) never forces test literals to encode shipped defaults (R48: no hardcoded
 * bucket names or ports in test recipes).
 */
@Component
public class S3UrlExpectations {

    private final String presignedPrefix;

    public S3UrlExpectations(@Value("${s3.endpoint}") String endpoint,
                                @Value("${s3.bucket}") String bucket) {
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.presignedPrefix = base + "/" + bucket + "/";
    }

    /** Prefix every presigned URL from the S3 provider must start with. */
    public String presignedPrefix() {
        return presignedPrefix;
    }
}
