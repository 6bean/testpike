package com.sixbean.testpike.api.ci.api.dto;

import java.util.Map;


public record CiRunInput(Long batch, String profile, Long projectId, Long envId,
                         Map<String, String> envOverrides) {
}
