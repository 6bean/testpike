package com.sixbean.testpike.core.sample.api.dto;


public record WordingAdoptionStat(long projectId, String projectName,
                                  long wordingRejects, long adoptedDiffs) {
}
