package com.sixbean.testpike.aiservice.matricesample.api.dto;

import java.util.List;
import java.util.Map;


public record MatrixSampleCandidate(
        String engineCode,
        String platformCode,
        String deviceKindCode,
        boolean addressable) {
}
