package com.sixbean.testpike.core.cluster.api.dto;

/**
 * Platform self-version view for the upgrade-center banner .
 * <p>{@code runningVersion}/{@code buildTime} come from this service's own
 * build-info (live read, never cached); {@code latestVersion} from the
 * authoritative release manifest table. Either side may be null — the banner
 * renders an honest default instead of failing.
 * <p>{@code verdict} codes (single spectrum = VersionCompare):
 * UP_TO_DATE / BEHIND / RUNNING_AHEAD_UNREGISTERED / NO_RELEASE / UNKNOWN_BUILD.
 */
public record PlatformVersionView(
        String runningVersion,
        String buildTime,
        String latestVersion,
        String verdict) {
}
