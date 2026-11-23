package com.sixbean.testpike.core.report.api.dto;


public record ChapterCoverage(String chapter, long total, long covered, double rate) {
}
