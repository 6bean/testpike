package com.sixbean.testpike.admin.cluster.api.dto;


public record ExecutorCompatView(Long id, String name, String version,
                                 Boolean compatible, String reason) {
}
