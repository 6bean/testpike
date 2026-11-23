package com.sixbean.testpike.core.audit.domain.component;

import java.time.LocalDateTime;


public record NestedCaseSnapshot(String zeta, String alpha, LocalDateTime createdAt, Integer count) {
}
