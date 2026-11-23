package com.sixbean.testpike.core.script.api.dto;

import com.sixbean.testpike.core.cases.api.dto.CaseVersionFieldChange;

import java.util.List;


public record ScriptVersionDiff(long caseId, int fromVersion, int toVersion,
                                boolean corrupted, String corruptedMessage,
                                List<CaseVersionFieldChange> changes) {
}
