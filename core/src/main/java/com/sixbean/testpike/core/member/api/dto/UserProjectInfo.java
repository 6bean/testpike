package com.sixbean.testpike.core.member.api.dto;


public record UserProjectInfo(Long projectId, String projectName, String projectCode,
                              int role, String roleLabel, String defaultLang) {
}
