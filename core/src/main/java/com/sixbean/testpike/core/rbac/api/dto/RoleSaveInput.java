package com.sixbean.testpike.core.rbac.api.dto;


public record RoleSaveInput(String name, String code, String description, int dataScope, int status) {
}
