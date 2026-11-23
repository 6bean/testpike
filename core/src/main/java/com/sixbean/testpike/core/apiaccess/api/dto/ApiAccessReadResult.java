package com.sixbean.testpike.core.apiaccess.api.dto;

/**
 * Outcome of one pass-through read.
 *
 * @param endpointKey  catalog key that was asked for
 * @param served       true when the door performed the call (a business error may still ride inside)
 * @param reason       what blocked the call when not served (switch, policy, argument, quota, scope)
 * @param httpStatus   status of the target call, 0 when nothing was sent
 * @param dataJson     the target's data payload as JSON text, clipped to the byte ceiling
 * @param truncated    true when the payload exceeded the byte or row ceiling (count reported below)
 * @param rowCount     list rows kept inside the payload, -1 when the payload is not a list
 * @param elapsedMs    target call duration
 */
public record ApiAccessReadResult(
        String endpointKey,
        boolean served,
        String reason,
        int httpStatus,
        String dataJson,
        boolean truncated,
        int rowCount,
        long elapsedMs) {

    /** Blocked outcome (nothing left this process). */
    public static ApiAccessReadResult blocked(String endpointKey, String reason) {
        return new ApiAccessReadResult(endpointKey, false, reason, 0, null, false, -1, 0L);
    }
}
