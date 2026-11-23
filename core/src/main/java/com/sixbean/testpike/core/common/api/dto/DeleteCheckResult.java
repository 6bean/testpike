package com.sixbean.testpike.core.common.api.dto;

import java.util.List;

/**
 * Deletion protection reference check result.
 *
 * @param refs list of reference details (empty list if no references)
 * @param deletable whether it can be deleted directly (true when refs is empty)
 * @param message human-readable summary message (for example, 'This project still has 3 environments and 156 test cases'; empty when deletable)
 */
public record DeleteCheckResult(List<DeleteRefInfo> refs, boolean deletable, String message) {

    public static DeleteCheckResult clean() {
        return new DeleteCheckResult(List.of(), true, "");
    }
}
