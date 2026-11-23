package com.sixbean.testpike.core.cases.domain.component;

import java.util.List;

/**
 * Automatically assign candidate members (C4): testing-capable project members scoped by the K6 same-source predicate.
 *
 * @param userId member ID
 * @param name display name (display_name fallback to username)
 * @param pendingLoad current under-review load (within the project, status∈(1,2) and assigned to this member, using the same definition as the workbench cards)
 * @param totalAssigned historical total assignments (full cross-project count, used for tie-breaking in load balancing)
 */
public record AutoAssignCandidate(long userId, String name, int pendingLoad, int totalAssigned) {
}
