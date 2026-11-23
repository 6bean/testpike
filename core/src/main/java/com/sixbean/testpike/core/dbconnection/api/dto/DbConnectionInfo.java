package com.sixbean.testpike.core.dbconnection.api.dto;

import java.time.LocalDateTime;

/**
 *
 * @param jdbcUrl JDBC URL
 */
public record DbConnectionInfo(long id, long projectId, long envId, String name, String dialect,
                               String jdbcUrl, String username, String password,
                               boolean readonly, int maxPool, int status,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {
}
