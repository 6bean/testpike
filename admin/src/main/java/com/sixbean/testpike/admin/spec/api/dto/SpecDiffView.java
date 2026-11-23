package com.sixbean.testpike.admin.spec.api.dto;

import java.util.List;


public record SpecDiffView(List<String> added, List<String> changed, List<String> removed) {
}
