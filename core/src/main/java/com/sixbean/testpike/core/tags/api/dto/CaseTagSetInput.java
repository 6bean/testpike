package com.sixbean.testpike.core.tags.api.dto;

import java.util.List;


public record CaseTagSetInput(long projectId, List<Long> tagIds) {
}
