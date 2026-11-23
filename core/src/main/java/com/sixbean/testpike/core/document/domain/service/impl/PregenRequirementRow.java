package com.sixbean.testpike.core.document.domain.service.impl;

/**
 * A live requirement row read for one pre-generation pass (AL batch segment
 * B). Soft-deleted requirements never appear here — the pass counts them as
 * missing against the snapshot ids.
 *
 * @param id requirement id
 * @param projectId owning project (attribution guard input)
 * @param title requirement title (rides the anchored prompt)
 * @param version current optimistic version (rides the anchored prompt)
 */
record PregenRequirementRow(long id, long projectId, String title, int version) {
}
