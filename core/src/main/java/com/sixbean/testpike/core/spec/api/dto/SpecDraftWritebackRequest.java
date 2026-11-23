package com.sixbean.testpike.core.spec.api.dto;

import com.sixbean.testpike.core.spec.domain.component.ApiSpecDraftItem;

import java.util.List;


public record SpecDraftWritebackRequest(List<ApiSpecDraftItem> items) {
}
