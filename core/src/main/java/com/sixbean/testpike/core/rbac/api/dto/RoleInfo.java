package com.sixbean.testpike.core.rbac.api.dto;

import java.time.LocalDateTime;


public record RoleInfo(long id, String name, String code, String description,
                       int dataScope, int status, int builtin, LocalDateTime createdAt) {
}
