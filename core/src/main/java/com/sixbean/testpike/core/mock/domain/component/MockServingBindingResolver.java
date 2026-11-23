package com.sixbean.testpike.core.mock.domain.component;

import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import com.sixbean.testpike.core.mock.api.dto.MockServingBinding;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInfo;
import com.sixbean.testpike.core.mock.domain.service.MockSpaceService;
import org.springframework.stereotype.Component;

/**
 * Resolves a mock space id into the serving binding a dispatching consumer
 * needs ; envelope assembly and claim-side injection share this
 * one gate — red line 6, no second copy).
 * <p>Published-only rule mirrors the snapshot contract: a draft/retired space
 * never binds traffic, so the resolver returns null (no key emitted — the
 * zero-drift trailing-key discipline keeps the old envelope shape intact).
 * <p>baseUrl = K8 mock.public-base-url + mock.traffic.base-path + "/" + spaceKey
 * — the same two keys and seed defaults the admin serving-url card reads;
 * the content pin always comes through MockSpaceService.snapshot so pinOf
 * stays the single version source.
 */
@Component
public class MockServingBindingResolver {

    /** Statuses: 0 draft / 1 published / 2 retired — only 1 serves. */
    private static final int STATUS_PUBLISHED = 1;
    /** Fallbacks mirror the V231/V228 seed defaults (admin bridge same posture). */
    private static final String DEFAULT_PUBLIC_BASE_URL = "http://localhost:18086";
    private static final String DEFAULT_TRAFFIC_BASE_PATH = "/mocks";

    private final MockSpaceService mockSpaceService;
    private final SystemConfigReader systemConfigReader;

    public MockServingBindingResolver(MockSpaceService mockSpaceService,
                                      SystemConfigReader systemConfigReader) {
        this.mockSpaceService = mockSpaceService;
        this.systemConfigReader = systemConfigReader;
    }

    /**
     * Serving binding for one space id, or null when there is nothing to bind:
     * missing id (0/null), deleted space, or a non-published lifecycle state.
     * Never throws on absence — an envelope must dispatch even when the bound
     * space was removed underneath the case (fail-open toward the real env,
     * matching the reference gate's delete-then-rebind posture).
     */
    public MockServingBinding resolve(Long spaceId) {
        if (spaceId == null || spaceId <= 0) {
            return null;
        }
        MockSpaceInfo space;
        try {
            space = mockSpaceService.get(spaceId);
        } catch (RuntimeException gone) {
            return null;
        }
        if (space.status() == null || space.status() != STATUS_PUBLISHED
                || space.spaceKey() == null || space.spaceKey().isBlank()) {
            return null;
        }
        String publicBase = systemConfigReader.getString("mock.public-base-url",
                DEFAULT_PUBLIC_BASE_URL);
        String trafficPrefix = systemConfigReader.getString("mock.traffic.base-path",
                DEFAULT_TRAFFIC_BASE_PATH);
        String baseUrl = trimTrailingSlash(publicBase) + normalizePrefix(trafficPrefix)
                + "/" + space.spaceKey();
        // pin source = snapshot service (its own serving gate re-checks the
        // published state, so the version read here cannot leak a draft pin)
        String pin = mockSpaceService.snapshot(space.spaceKey()).version();
        return new MockServingBinding(space.spaceKey(), baseUrl, pin);
    }

    private static String trimTrailingSlash(String raw) {
        String v = raw == null ? "" : raw.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    /** Prefix must start with exactly one slash and carry no trailing one. */
    private static String normalizePrefix(String raw) {
        String v = raw == null ? "" : raw.trim();
        while (v.startsWith("//")) {
            v = v.substring(1);
        }
        if (!v.startsWith("/")) {
            v = "/" + v;
        }
        while (v.length() > 1 && v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return "/".equals(v) ? "" : v;
    }
}
