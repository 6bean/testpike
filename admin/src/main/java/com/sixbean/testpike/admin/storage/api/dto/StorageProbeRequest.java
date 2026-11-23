package com.sixbean.testpike.admin.storage.api.dto;


public record StorageProbeRequest(String provider, String localRoot,
                                  String ossEndpoint, String ossBucket,
                                  String ossAccessKeyId, String ossAccessKeySecret) {
}
