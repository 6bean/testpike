package com.sixbean.testpike.core.perf.api.dto;

import java.util.List;


public record PerfWhitelistInput(Long envId, Integer maxConcurrency, List<String> allowedModes) {
}
