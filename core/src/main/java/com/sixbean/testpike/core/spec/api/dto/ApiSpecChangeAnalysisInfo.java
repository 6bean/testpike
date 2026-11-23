package com.sixbean.testpike.core.spec.api.dto;

import java.util.List;

/**
 * One contract change-analysis queue row (review-queue read model).
 *
 * @param id             tp_api_spec_change_analysis.id
 * @param projectId      owning project (H1 isolation scope)
 * @param oldSpecId      previous contract version the diff was taken against
 * @param newSpecId      contract version that triggered the analysis
 * @param changes        endpoint-level change rows with closed impact lists
 * @param status         0 = pending review, 1 = handled (SpecChangeAnalysisStatus)
 * @param statusLabel    human-readable status label from the single-source code class
 * @param detectedSource upload | fetch | poll — which entry point landed the version
 * @param createdBy      operator who landed the version
 * @param createdAt      creation time (ViewFormats string convention)
 * @param handledBy      disposition recorder (null until handled)
 * @param handledAt      disposition time (null until handled)
 * @param handleNote     disposition note (trace only)
 */
public record ApiSpecChangeAnalysisInfo(Long id, long projectId, long oldSpecId, long newSpecId,
                                        List<ApiSpecChangeItem> changes, int status,
                                        String statusLabel, String detectedSource,
                                        Long createdBy, String createdAt,
                                        Long handledBy, String handledAt, String handleNote) {
}
