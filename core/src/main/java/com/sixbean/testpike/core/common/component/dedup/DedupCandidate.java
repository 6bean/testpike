package com.sixbean.testpike.core.common.component.dedup;

/**
 * One existing row the dedup gate considers similar to the probe title.
 *
 * @param id    existing row id (named to the operator, never auto-merged)
 * @param title existing row title as stored
 * @param score similarity in [0,1]; deterministic L1 matches report 1.0
 */
public record DedupCandidate(long id, String title, double score) {
}
