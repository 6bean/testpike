package com.sixbean.testpike.admin.cases.api.dto;

import java.util.List;


public record BatchEditResultView(List<Long> succeeded, List<BatchEditFailureItemView> failures) {
}
