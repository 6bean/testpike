package com.sixbean.testpike.core.spec.api.dto;

import com.sixbean.testpike.core.spec.domain.component.ApiSpecDraftItem;

import java.util.List;

/**
 *
 * @param id tp_api_spec_preview.id
 */
public record ApiSpecPreviewInfo(Long id, Long projectId, Long specId, Long sourceDocumentId,
                                 String status,
                                 List<ApiSpecDraftItem> items, String createdAt) {
}
