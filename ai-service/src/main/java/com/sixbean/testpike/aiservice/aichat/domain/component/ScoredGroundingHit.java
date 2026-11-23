package com.sixbean.testpike.aiservice.aichat.domain.component;

/**
 * A grounding candidate paired with its final assembly score .
 * Independent file — the code rules forbid inner types.
 *
 * @param hit the candidate being scored
 * @param score retrieval base + lexical overlap + family bonus
 */
record ScoredGroundingHit(GroundingHit hit, double score) {
}
