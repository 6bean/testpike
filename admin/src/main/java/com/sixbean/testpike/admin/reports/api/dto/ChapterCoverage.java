package com.sixbean.testpike.admin.reports.api.dto;


public record ChapterCoverage(String chapter, long total, long covered, double rate) {
}
