package com.sixbean.testpike.admin.spec.api.dto;

import java.util.List;

/**
 * Contract change-analysis queue row for the admin display side (name-mirrored from
 * core ApiSpecChangeAnalysisInfo — 13 fields). statusLabel and the formatted time
 * strings are derived in core only; admin/front-end must not re-map them a second time.
 *
 * @param status 0 = pending review, 1 = handled
 */
public record ApiSpecChangeAnalysisView(Long id, Long projectId, Long oldSpecId, Long newSpecId,
                                        List<ApiSpecChangeItemView> changes, int status,
                                        String statusLabel, String detectedSource,
                                        Long createdBy, String createdAt,
                                        Long handledBy, String handledAt, String handleNote) {
}
