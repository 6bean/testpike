package com.sixbean.testpike.admin.rbac.api.dto;


public record RoleSaveRequest(String name, String code, String description, int dataScope, int status) {
}
