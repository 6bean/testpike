package com.sixbean.testpike.admin.core.api.dto;


public record UserProjectInfo(Long projectId, String projectName, String projectCode,
                              int role, String roleLabel, String defaultLang) {
}
