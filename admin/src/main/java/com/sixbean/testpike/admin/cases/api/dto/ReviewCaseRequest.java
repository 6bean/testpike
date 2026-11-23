package com.sixbean.testpike.admin.cases.api.dto;

import java.util.List;


public record ReviewCaseRequest(String action, String remark, Integer version,
                                List<String> feedbackTypes) {
}
