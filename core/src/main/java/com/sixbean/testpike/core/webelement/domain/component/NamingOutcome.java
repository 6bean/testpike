package com.sixbean.testpike.core.webelement.domain.component;

/**
 * Outcome of one naming relay over a candidate pool (whole-site ledger
 * tallying relay face): the pool to ingest plus the honest
 * naming-supply reading.
 * <p>{@code degraded} is true only when the pool DID carry nameable
 * candidates but the naming door could not deliver names (transport
 * outage, empty name map, or a failed rewrite) — the rule-collected
 * placeholder names kept the survey usable at lower naming quality. A
 * malformed pool or a pool with nothing to name is an honest
 * not-degraded: no naming was owed, so none was cut off.
 */
public record NamingOutcome(String pool, boolean degraded) {
}
