package com.sixbean.testpike.admin.spec.api.dto;

import java.util.List;


public record ApiSpecPreviewView(Long id, Long projectId, Long specId, String status,
                                 List<ApiSpecDraftItemView> items, String createdAt) {
}
