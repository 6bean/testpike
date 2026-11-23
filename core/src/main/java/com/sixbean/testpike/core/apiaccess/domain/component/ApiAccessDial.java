package com.sixbean.testpike.core.apiaccess.domain.component;

/**
 * Result of one dial against this application's own door.
 *
 * @param status     HTTP status of the target call
 * @param body       response text, already capped by the caller's byte ceiling
 * @param elapsedMs  wall time of the call
 * @param error      why nothing usable came back (interrupted, unreachable); null when the call ran
 */
public record ApiAccessDial(int status, String body, long elapsedMs, String error) {
}
