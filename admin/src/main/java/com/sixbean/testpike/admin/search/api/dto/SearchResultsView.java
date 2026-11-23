package com.sixbean.testpike.admin.search.api.dto;

import java.util.List;


public record SearchResultsView(List<SearchItemView> cases, List<SearchItemView> requirements,
                                List<SearchItemView> defects, List<SearchItemView> projects) {
}
