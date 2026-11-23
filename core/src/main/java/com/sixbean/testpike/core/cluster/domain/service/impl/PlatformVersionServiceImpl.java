package com.sixbean.testpike.core.cluster.domain.service.impl;

import com.sixbean.testpike.core.cluster.api.dto.PlatformVersionView;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseManifestInfo;
import com.sixbean.testpike.core.cluster.domain.service.PlatformVersionService;
import com.sixbean.testpike.core.cluster.domain.service.ReleaseManifestService;
import com.sixbean.testpike.core.common.domain.component.VersionCompare;
import org.springframework.lang.Nullable;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;

/**
 * Verdict single-spectrum implementation : running build-info version vs
 * latest manifest, compared through {@code VersionCompare} — no new comparison
 * code allowed here (judgement-spectrum single-source red line).
 * <p>Honest defaults: no build-info on classpath (dev run without the maven
 * goal) = UNKNOWN_BUILD with null running version; empty manifest table =
 * NO_RELEASE; running ahead of the manifest highest = RUNNING_AHEAD_UNREGISTERED
 * so a missed registration becomes a visible signal instead of silence.
 */
@Service
public class PlatformVersionServiceImpl implements PlatformVersionService {

    private static final DateTimeFormatter BUILD_TIME_FORMAT = DateTimeFormatter.ISO_INSTANT;

    private final ReleaseManifestService releaseManifestService;
    @Nullable
    private final BuildProperties buildProperties;

    public PlatformVersionServiceImpl(ReleaseManifestService releaseManifestService,
                                      @Nullable BuildProperties buildProperties) {
        this.releaseManifestService = releaseManifestService;
        this.buildProperties = buildProperties;
    }

    @Override
    public PlatformVersionView platformVersion() {
        BuildProperties props = buildProperties;
        String running = props == null ? null : props.getVersion();
        String builtAt = props == null || props.getTime() == null
                ? null : BUILD_TIME_FORMAT.format(props.getTime());
        ReleaseManifestInfo latest = releaseManifestService.latest();
        String latestVersion = latest == null ? null : latest.version();
        return new PlatformVersionView(running, builtAt, latestVersion,
                verdictOf(running, latestVersion));
    }

    private String verdictOf(String running, String latestVersion) {
        if (running == null || running.isBlank()) {
            return "UNKNOWN_BUILD";
        }
        if (latestVersion == null || latestVersion.isBlank()) {
            return "NO_RELEASE";
        }
        int cmp = VersionCompare.compare(running, latestVersion);
        if (cmp > 0) {
            return "RUNNING_AHEAD_UNREGISTERED";
        }
        return cmp == 0 ? "UP_TO_DATE" : "BEHIND";
    }
}
