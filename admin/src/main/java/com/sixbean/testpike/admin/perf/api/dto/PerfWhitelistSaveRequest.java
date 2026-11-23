package com.sixbean.testpike.admin.perf.api.dto;

import java.util.List;


public record PerfWhitelistSaveRequest(Long projectId, Long envId, Integer maxConcurrency,
                                       List<String> allowedModes) {
}
