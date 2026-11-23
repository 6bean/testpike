package com.sixbean.testpike.admin.storage.api.dto;


public record StorageConfigView(String provider, String keyPrefix, String urlTtl, String localRoot,
                                String ossEndpoint, String ossBucket,
                                String ossAccessKeyId, String ossAccessKeySecret) {
}
