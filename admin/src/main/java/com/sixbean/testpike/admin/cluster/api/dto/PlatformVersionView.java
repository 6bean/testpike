package com.sixbean.testpike.admin.cluster.api.dto;


public record PlatformVersionView(
        String runningVersion,
        String buildTime,
        String latestVersion,
        String verdict) {
}
