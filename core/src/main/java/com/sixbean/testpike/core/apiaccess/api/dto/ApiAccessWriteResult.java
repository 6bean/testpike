package com.sixbean.testpike.core.apiaccess.api.dto;

/**
 * Outcome of one confirmed change landing.
 *
 * @param endpointKey  entry that was asked for
 * @param landed       true when the change was executed
 * @param reason       what stopped it (switch, policy, ownership, budget, target error)
 * @param httpStatus   target status, 0 when nothing was sent
 * @param producedId   id the target reported for the row it created or changed, null when absent —
 *                     the draft card rewrites its anchor to this so the human can jump straight there
 * @param responseJson clipped target response payload (for the card's receipt line)
 * @param elapsedMs    target call duration
 */
public record ApiAccessWriteResult(
        String endpointKey,
        boolean landed,
        String reason,
        int httpStatus,
        Long producedId,
        String responseJson,
        long elapsedMs) {

    /** Refused landing: nothing left the process. */
    public static ApiAccessWriteResult blocked(String endpointKey, String reason) {
        return new ApiAccessWriteResult(endpointKey, false, reason, 0, null, null, 0L);
    }
}
