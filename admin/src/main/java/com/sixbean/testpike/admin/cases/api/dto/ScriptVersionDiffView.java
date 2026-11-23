package com.sixbean.testpike.admin.cases.api.dto;

import java.util.List;


public record ScriptVersionDiffView(Long caseId, Integer fromVersion, Integer toVersion,
                                    boolean corrupted, String corruptedMessage,
                                    List<CaseVersionChangeView> changes) {
}
