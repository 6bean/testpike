package com.sixbean.testpike.admin.appauto.api.dto;


public record AppPackageSaveRequest(
        Long projectId,
        String name,
        String packageName,
        String version,
        String platformCode,
        String permissionRuleRef) {
}
