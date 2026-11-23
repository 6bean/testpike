package com.sixbean.testpike.admin.ai.api.dto;

import java.util.List;

/**
 *
 * @param changeType add/modify/delete
 */
public record DocChangeItemView(String chapter, String changeType,
                                List<Long> requirementIds, List<Long> caseIds,
                                boolean chapterLocatable) {
}
