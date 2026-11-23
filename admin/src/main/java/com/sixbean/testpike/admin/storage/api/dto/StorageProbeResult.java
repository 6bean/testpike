package com.sixbean.testpike.admin.storage.api.dto;


public record StorageProbeResult(String provider, boolean success, String category,
                                 String message, String probeKey) {
}
