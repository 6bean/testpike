package com.sixbean.testpike.core.dbconnection.api.dto;


public record DbConnectionInput(long envId, String name, String dialect, String jdbcUrl,
                                String username, String password,
                                boolean readonly, Integer maxPool) {
}
