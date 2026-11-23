package com.sixbean.testpike.core.linkage.api.dto;

/**
 * Ownership facts resolved from the upstream object row itself: the project
 * the object belongs to, the person who created (saved) it, and its title
 * for readable notification copy (AN batch ; extracted from the panel
 * read so the urge scan and the display never diverge — one judge, one
 * implementation).
 */
public record UpstreamOwnership(long projectId, Long createdBy, String title) {
}
