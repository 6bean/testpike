package com.sixbean.testpike.core.credential.api.dto;

/**
 * Outcome of one credential "test now" run. The run token keys the event
 * tail; verdict/grade ride as first-class fields (the evidence grade
 * must never be buried in the trail note) and {@code injections} is the
 * three-surface reconciliation count (URL + request headers + body template)
 * the four-state rule branches on.
 *
 * @param runToken   keys the process-event tail window
 * @param verdict    shared transport verdict name (VALID / EXPIRED / UNKNOWN / FAILED)
 * @param grade      credential evidence grade name, null when no request ran (FAILED)
 * @param gradeHuman badge wording with the fix-up pointer (three elements rule)
 * @param note       full verdict sentence (reason + fix entry, three elements rule)
 * @param injections number of vault references actually consumed on the wire
 */
public record CredentialTestOutcome(String runToken, String verdict, String grade,
                                    String gradeHuman, String note, int injections) {
}
