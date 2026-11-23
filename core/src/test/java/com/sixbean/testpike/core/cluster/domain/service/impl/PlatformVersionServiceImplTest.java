package com.sixbean.testpike.core.cluster.domain.service.impl;

import com.sixbean.testpike.core.cluster.api.dto.PlatformVersionView;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseManifestInfo;
import com.sixbean.testpike.core.cluster.domain.service.ReleaseManifestService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L2 for the platform self-version verdict :
 * running build version vs latest authoritative release manifest,
 * compared through the single VersionCompare spectrum.
 * Verdict codes consumed by the upgrade-center banner:
 * UP_TO_DATE / BEHIND / RUNNING_AHEAD_UNREGISTERED / NO_RELEASE / UNKNOWN_BUILD.
 */
class PlatformVersionServiceImplTest {

    private BuildProperties buildProps(String version) {
        // Keys match the plain (unprefixed) format spring-boot-maven-plugin
        // 3.x writes into build-info.properties (see on-disk generated file).
        Properties props = new Properties();
        props.setProperty("version", version);
        props.setProperty("time", "2026-10-12T10:00:00Z");
        return new BuildProperties(props);
    }

    private ReleaseManifestInfo manifest(String version) {
        return new ReleaseManifestInfo(1L, version, null, null, null, null, null, "manual", null,
                null, null, null);
    }

    @Test
    void runningBehindLatestReleaseIsBehind() {
        ReleaseManifestService manifests = mock(ReleaseManifestService.class);
        when(manifests.latest()).thenReturn(manifest("1.2.0"));
        PlatformVersionServiceImpl svc = new PlatformVersionServiceImpl(manifests, buildProps("1.0.0"));
        PlatformVersionView view = svc.platformVersion();
        assertThat(view.verdict()).isEqualTo("BEHIND");
        assertThat(view.runningVersion()).isEqualTo("1.0.0");
        assertThat(view.latestVersion()).isEqualTo("1.2.0");
        assertThat(view.buildTime()).isNotBlank();
    }

    @Test
    void runningAheadOfManifestIsUnregisteredReleaseSignal() {
        ReleaseManifestService manifests = mock(ReleaseManifestService.class);
        when(manifests.latest()).thenReturn(manifest("1.1.0"));
        PlatformVersionServiceImpl svc = new PlatformVersionServiceImpl(manifests, buildProps("2.0.0"));
        assertThat(svc.platformVersion().verdict()).isEqualTo("RUNNING_AHEAD_UNREGISTERED");
    }

    @Test
    void runningEqualToLatestReleaseIsUpToDate() {
        ReleaseManifestService manifests = mock(ReleaseManifestService.class);
        when(manifests.latest()).thenReturn(manifest("1.2.0"));
        PlatformVersionServiceImpl svc = new PlatformVersionServiceImpl(manifests, buildProps("1.2.0"));
        assertThat(svc.platformVersion().verdict()).isEqualTo("UP_TO_DATE");
    }

    @Test
    void snapshotSuffixComparesOnNumericSegments() {
        ReleaseManifestService manifests = mock(ReleaseManifestService.class);
        when(manifests.latest()).thenReturn(manifest("1.2.0"));
        PlatformVersionServiceImpl svc = new PlatformVersionServiceImpl(manifests, buildProps("1.0.0-SNAPSHOT"));
        assertThat(svc.platformVersion().verdict()).isEqualTo("BEHIND");
    }

    @Test
    void missingBuildInfoIsHonestUnknownBuild() {
        ReleaseManifestService manifests = mock(ReleaseManifestService.class);
        when(manifests.latest()).thenReturn(manifest("1.2.0"));
        PlatformVersionServiceImpl svc = new PlatformVersionServiceImpl(manifests, null);
        PlatformVersionView view = svc.platformVersion();
        assertThat(view.verdict()).isEqualTo("UNKNOWN_BUILD");
        assertThat(view.runningVersion()).isNull();
    }

    @Test
    void emptyManifestTableIsNoReleaseEvenWithBuildInfo() {
        ReleaseManifestService manifests = mock(ReleaseManifestService.class);
        when(manifests.latest()).thenReturn(null);
        PlatformVersionServiceImpl svc = new PlatformVersionServiceImpl(manifests, buildProps("1.0.0"));
        PlatformVersionView view = svc.platformVersion();
        assertThat(view.verdict()).isEqualTo("NO_RELEASE");
        assertThat(view.runningVersion()).isEqualTo("1.0.0");
    }
}
