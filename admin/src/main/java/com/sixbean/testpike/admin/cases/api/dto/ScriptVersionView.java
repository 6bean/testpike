package com.sixbean.testpike.admin.cases.api.dto;

import java.time.LocalDateTime;


public record ScriptVersionView(Long id, Long caseId, Long scriptId, Integer caseVersion,
                                Integer scriptVersion, String schemaVersion, String reason,
                                LocalDateTime createdAt, boolean corrupted) {
}
