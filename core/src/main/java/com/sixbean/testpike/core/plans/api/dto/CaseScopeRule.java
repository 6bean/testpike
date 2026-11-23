package com.sixbean.testpike.core.plans.api.dto;

import java.util.List;


public record CaseScopeRule(List<Integer> phases, List<Integer> priorities,
                            String keyword, List<Long> tagIds, List<Long> explicitCaseIds) {

    
    public CaseScopeRule {
        phases = phases == null ? List.of() : phases;
        priorities = priorities == null ? List.of() : priorities;
        tagIds = tagIds == null ? List.of() : tagIds;
        explicitCaseIds = explicitCaseIds == null ? List.of() : explicitCaseIds;
    }

 
    public static CaseScopeRule of(List<Integer> phases, List<Integer> priorities,
                                   String keyword, List<Long> tagIds) {
        return of(phases, priorities, keyword, tagIds, List.of());
    }

    
    public static CaseScopeRule of(List<Integer> phases, List<Integer> priorities,
                                   String keyword, List<Long> tagIds, List<Long> explicitCaseIds) {
        CaseScopeRule rule = new CaseScopeRule(phases, priorities, keyword, tagIds, explicitCaseIds);
        for (Integer p : rule.phases) {
            if (p == null || p < 1 || p > 4) {
                throw new com.sixbean.testpike.common.exception.ServiceException(
                        "范围规则阶段取值仅支持 1=冒烟 2=功能 3=回归 4=发布验证",
                        com.sixbean.testpike.common.exception.BizErrorCode.VALIDATION_ERROR);
            }
        }
        for (Integer p : rule.priorities) {
            if (p == null || p < 0 || p > 2) {
                throw new com.sixbean.testpike.common.exception.ServiceException(
                        "范围规则优先级取值仅支持 0=P0 1=P1 2=P2",
                        com.sixbean.testpike.common.exception.BizErrorCode.VALIDATION_ERROR);
            }
        }
        return rule;
    }

    
    public boolean isEmptyRule() {
        return phases.isEmpty() && priorities.isEmpty()
                && (keyword == null || keyword.isBlank()) && tagIds.isEmpty()
                && explicitCaseIds.isEmpty();
    }
}
