package com.sixbean.testpike.core.linkage.domain.service;

/**
 * Identifiers of one seeded linkage fixture chain (project -> case -> script).
 * The linkage tests each seed their own chain under a unique project code and
 * purge it bottom-up afterwards; this record carries the three ids between the
 * seed and the purge without every test re-declaring the same triple.
 */
public record LinkageCaseChain(long projectId, long caseId, long scriptId) {
}
