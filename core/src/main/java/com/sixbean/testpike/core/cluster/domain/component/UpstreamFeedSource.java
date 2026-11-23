package com.sixbean.testpike.core.cluster.domain.component;

/**
 * Upstream version source registration (frozen documentation: engines = their official sources/npm/pypi/GitHub releases).
 * <p>Data-driven registry: family → (source type + source locator); runtime management probe for a new engine = add one registry row,
 * Zero code changes (isomorphic to inserting a row in the component family list; the red-line rule forbids two switch implementations).
 * <p>Unregistered = an honest "not probed" record (families without public upstream version sources, such as WeChat Developer Tools/hdc),
 * Do not misrepresent families that cannot be probed as managed.
 *
 * @param kind source type (determines the JSON parsing approach)
 * @param ref source locator (npm=package name / pypi=package name / github=repos/{owner}/{repo})
 */
public record UpstreamFeedSource(UpstreamFeedKind kind, String ref) {
}
