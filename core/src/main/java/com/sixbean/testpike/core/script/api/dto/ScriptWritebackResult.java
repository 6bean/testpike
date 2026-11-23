package com.sixbean.testpike.core.script.api.dto;

/**
 * Result of a successful off-platform script write-back.
 *
 * @param caseId        target case id
 * @param scriptVersion new script row version after the write (1 for the first ever script)
 * @param caseStatus    case status after the write; always the pending-review code
 * @param evidenceGrade grade landed on the case row (1 self-report / 2 evidence on file)
 */
public record ScriptWritebackResult(long caseId, int scriptVersion, int caseStatus, int evidenceGrade) {
}
