package com.sixbean.testpike.core.spec.domain.component;

import java.util.List;


public record ParsedApiSpec(
        String specFormat,
        String title,
        String version,
        List<ApiEndpoint> endpoints) {
}
