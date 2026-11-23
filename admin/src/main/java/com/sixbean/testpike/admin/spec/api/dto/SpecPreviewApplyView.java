package com.sixbean.testpike.admin.spec.api.dto;

import java.util.List;


public record SpecPreviewApplyView(int createdRequests, int skipped, List<Long> requestIds) {
}
