package com.sixbean.testpike.core.document.api.dto;

import java.util.List;


public record DocChangeItem(String chapter, String changeType,
                            List<Long> requirementIds, List<Long> caseIds,
                            boolean chapterLocatable) {
}
