package com.sixbean.testpike.admin.plans.api.dto;

import java.util.List;


public record CaseScopeRuleRequest(List<Integer> phases, List<Integer> priorities,
                                   String keyword, List<Long> tagIds) {
}
