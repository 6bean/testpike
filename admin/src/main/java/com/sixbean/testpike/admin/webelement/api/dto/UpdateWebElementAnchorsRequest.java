package com.sixbean.testpike.admin.webelement.api.dto;

import java.util.List;

/**
 * Anchor-set replacement body: the whole ordered set is rewritten at once
 * (partial merges would silently reorder the runtime trial sequence), and
 * the revision token refuses stale writes with the shared 40901 posture.
 */
public record UpdateWebElementAnchorsRequest(List<WebElementAnchor> anchors, int expectedRevision) {
}
