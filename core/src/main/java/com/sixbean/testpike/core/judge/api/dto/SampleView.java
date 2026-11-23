package com.sixbean.testpike.core.judge.api.dto;

import java.util.List;


public record SampleView(Long id, String setName, Integer version, String snapshotDigest,
                         String input, String expected, List<String> referenceDocs,
                         String source, Integer sensitive, Long createdBy, String createdAt) {
}
