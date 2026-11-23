package com.sixbean.testpike.aiservice.judge.infrastructure.remote;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;


@JsonIgnoreProperties(ignoreUnknown = true)
public record JudgeSampleBrief(Long id, String input, String expected,
                               List<String> referenceDocs, Integer sensitive) {
}
