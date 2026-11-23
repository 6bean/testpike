package com.sixbean.testpike.core.spec.domain.component;

import java.util.List;


public record SpecDiff(List<String> added, List<String> changed, List<String> removed) {

    public static SpecDiff empty() {
        return new SpecDiff(List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return added.isEmpty() && changed.isEmpty() && removed.isEmpty();
    }
}
