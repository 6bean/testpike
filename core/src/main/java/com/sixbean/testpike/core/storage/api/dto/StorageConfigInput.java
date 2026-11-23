package com.sixbean.testpike.core.storage.api.dto;


public record StorageConfigInput(String provider, String keyPrefix, String urlTtl, String localRoot,
                                 String ossEndpoint, String ossBucket,
                                 String ossAccessKeyId, String ossAccessKeySecret) {
}
