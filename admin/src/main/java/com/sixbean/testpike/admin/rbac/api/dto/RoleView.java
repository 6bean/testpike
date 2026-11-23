package com.sixbean.testpike.admin.rbac.api.dto;

import java.time.LocalDateTime;


public record RoleView(long id, String name, String code, String description,
                       int dataScope, int status, int builtin, LocalDateTime createdAt) {
}
