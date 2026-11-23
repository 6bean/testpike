package com.sixbean.testpike.core.cluster.domain.component;

/**
 * Upstream version probe source category (frozen document: engine=respective official sources/npm/pypi/GitHub releases).
 * <p>Each source uses a different latest-version JSON parsing approach (npm=dist-tags.latest / pypi=info.version /
 * github=releases tag_name), dispatched by {@code ClusterUpstreamProbeServiceImpl} according to type.
 * Subclass inheritance is forbidden.
 */
public enum UpstreamFeedKind {

    /** npm registry (package metadata dist-tags.latest). */
    NPM,

    /** PyPI (info.version from /pypi/&lt;pkg&gt;/json). */
    PYPI,

    /** GitHub releases latest (tag_name with v prefix removed). */
    GITHUB_RELEASES
}
