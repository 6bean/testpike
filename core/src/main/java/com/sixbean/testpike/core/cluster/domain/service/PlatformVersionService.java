package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.cluster.api.dto.PlatformVersionView;

/**
 * Platform self-version perception : what version am I running
 * and how does it sit against the latest authoritative release.
 */
public interface PlatformVersionService {

    /** Compare running build version vs latest manifest release; never throws on missing sides. */
    PlatformVersionView platformVersion();
}
