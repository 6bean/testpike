package com.sixbean.testpike.admin.cases.api.dto;

import java.util.List;


public record AutoAssignResultView(int assignedCount, List<AutoAssignRowView> byAssignee) {
}
