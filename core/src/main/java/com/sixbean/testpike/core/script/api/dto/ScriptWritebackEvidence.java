package com.sixbean.testpike.core.script.api.dto;

/**
 * Evidence package attached to an off-platform script write-back.
 * <p>Why this exists: a script written back by an outside agent carries no
 * platform execution record yet, only what the agent observed on its own
 * machine. Presence of this envelope grades the case as "evidence on file"
 * (grade 2) instead of a bare self-report (grade 1); the log itself is not
 * uploaded, only its SHA-256 digest so reviewers can match an exported log.
 *
 * @param ranAt          agent-side run timestamp (ISO-8601 text as reported)
 * @param envFingerprint free-form environment identity (OS/browser/runner)
 * @param passed         agent-reported passed step count (nullable)
 * @param failed         agent-reported failed step count (nullable)
 * @param logSha256      digest of the full run log the agent keeps locally
 */
public record ScriptWritebackEvidence(String ranAt, String envFingerprint,
                                      Integer passed, Integer failed, String logSha256) {
}
