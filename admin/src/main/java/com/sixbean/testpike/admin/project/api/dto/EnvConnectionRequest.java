package com.sixbean.testpike.admin.project.api.dto;


public record EnvConnectionRequest(long envId, String name, String dialect, String jdbcUrl,
                                   String username, String password,
                                   boolean readonly, Integer maxPool) {
}
