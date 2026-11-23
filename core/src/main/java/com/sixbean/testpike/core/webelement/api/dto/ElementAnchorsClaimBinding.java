package com.sixbean.testpike.core.webelement.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Claim-envelope trailing binding for element references (WE batch S1). The
 * dispatch snapshot carries the ledger anchor sets so the executor never calls
 * back into core — the execution chain stays decoupled from the ledger store.
 *
 * @param allowCandidate echoes the draft-trial declaration found in the action
 * JSON; candidates are only ever present when true
 * @param elements referenced element name → ordered ledger snapshots
 * (confirmed rows first; an empty list is the honest
 * "referenced but not registered" answer)
 * @param reAnchorSimFloor visual-similarity floor for an automatic direct
 * re-anchor . The
 * value is read from the hot-updatable config key
 * {@code element.re-anchor.similarity-floor} at claim
 * time; the executor compares against this floor and
 * never re-reads the config (single source, red line 6).
 */
public record ElementAnchorsClaimBinding(boolean allowCandidate,
                                         Map<String, List<ElementSnapshot>> elements,
                                         Double reAnchorSimFloor) {
}
