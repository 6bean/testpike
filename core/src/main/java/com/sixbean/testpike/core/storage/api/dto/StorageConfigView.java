package com.sixbean.testpike.core.storage.api.dto;

/**
 *
 * @param provider local / s3 / aliyun-oss
 * @param ossEndpoint OSS endpoint
 * @param ossBucket OSS bucket
 */
public record StorageConfigView(String provider, String keyPrefix, String urlTtl, String localRoot,
                                String ossEndpoint, String ossBucket,
                                String ossAccessKeyId, String ossAccessKeySecret) {
}
