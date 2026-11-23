package com.sixbean.testpike.core.spec.api.dto;

import java.util.List;


public record SpecPreviewApplyResult(int createdRequests, int skipped, List<Long> requestIds) {
}
