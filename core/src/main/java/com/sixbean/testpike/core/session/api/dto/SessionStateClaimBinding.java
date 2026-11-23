package com.sixbean.testpike.core.session.api.dto;

/**
 * Claim-envelope login-state binding for browser tasks (03 protocol 2.3
 * trailing key sessionState, face 2).
 *
 * <p>Materialised side (resolution succeeded): {@code stateName} and
 * {@code kind} describe the ledger row and {@code storageState} is the
 * Playwright storage_state document produced by the single translation source
 * SessionStateSupport — the executor consumes it as-is and never re-derives
 * cookies, domains or expiry (AV design doc, M4 ruling).
 *
 * <p>Refused side (selector unknown, ambiguous role, token-kind row, empty
 * snapshot): {@code storageState} stays null and {@code refusal} carries the
 * plain-language remedy worded at the policy source. The claim itself never
 * throws for one bad declaration — poisoning the whole claim round for every
 * other task would be the worse failure; the executor fails just this task
 * with the relayed wording.
 *
 * <p>⚠️ storageState embeds session credentials in plaintext: no log line at
 * any layer may print this record (same vault red line as envVars).
 */
public record SessionStateClaimBinding(String stateName, String kind,
                                       String storageState, String refusal) {
}
