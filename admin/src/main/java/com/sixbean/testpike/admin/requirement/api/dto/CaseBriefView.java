package com.sixbean.testpike.admin.requirement.api.dto;

/**
 *
 * @param priority 0=P0 1=P1 2=P2
 */
public record CaseBriefView(Long id, String caseNo, String title, Integer status,
                            Integer priority, Integer confidence) {
}
