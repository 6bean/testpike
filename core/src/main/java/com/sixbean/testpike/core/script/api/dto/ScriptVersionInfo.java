package com.sixbean.testpike.core.script.api.dto;

import java.time.LocalDateTime;


public record ScriptVersionInfo(long id, long caseId, long scriptId, int caseVersion,
                                int scriptVersion, String schemaVersion, String reason,
                                LocalDateTime createdAt, boolean corrupted) {
}
