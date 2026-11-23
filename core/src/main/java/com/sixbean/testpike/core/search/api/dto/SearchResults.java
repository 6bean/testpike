package com.sixbean.testpike.core.search.api.dto;

import java.util.List;


public record SearchResults(List<SearchItem> cases, List<SearchItem> requirements,
                            List<SearchItem> defects, List<SearchItem> projects) {

    public SearchResults {
        cases = cases == null ? List.of() : List.copyOf(cases);
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        defects = defects == null ? List.of() : List.copyOf(defects);
        projects = projects == null ? List.of() : List.copyOf(projects);
    }
}
