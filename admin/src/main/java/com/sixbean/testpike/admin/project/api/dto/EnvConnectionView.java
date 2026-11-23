package com.sixbean.testpike.admin.project.api.dto;

import java.time.LocalDateTime;


public record EnvConnectionView(long id, long projectId, long envId, String name, String dialect,
                                String jdbcUrl, String username, String password,
                                boolean readonly, int maxPool, int status,
                                LocalDateTime createdAt, LocalDateTime updatedAt) {
}
