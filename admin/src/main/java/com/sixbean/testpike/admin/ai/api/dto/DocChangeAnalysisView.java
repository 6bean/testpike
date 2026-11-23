package com.sixbean.testpike.admin.ai.api.dto;

import java.util.List;


public record DocChangeAnalysisView(Long id, Long projectId, Long oldDocumentId, Long newDocumentId,
                                    List<DocChangeItemView> changes, boolean chapterLocatable,
                                    int status, String statusLabel,
                                    Long createdBy, String createdAt,
                                    Long handledBy, String handledAt, String handleNote) {
}
