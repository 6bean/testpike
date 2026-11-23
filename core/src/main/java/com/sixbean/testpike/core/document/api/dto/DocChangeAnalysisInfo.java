package com.sixbean.testpike.core.document.api.dto;

import java.util.List;


public record DocChangeAnalysisInfo(Long id, long projectId, long oldDocumentId, long newDocumentId,
                                    List<DocChangeItem> changes, boolean chapterLocatable,
                                    int status, String statusLabel,
                                    Long createdBy, String createdAt,
                                    Long handledBy, String handledAt, String handleNote) {
}
