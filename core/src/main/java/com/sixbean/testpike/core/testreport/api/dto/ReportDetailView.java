package com.sixbean.testpike.core.testreport.api.dto;

import java.util.List;


public record ReportDetailView(long id, long projectId, Long planId, String planName, String title,
                               int rangeType, String rangeLabel, Scope scope, Results results,
                               DefectDist defectDist, List<EnvVersion> envVersions,
                               Conclusion conclusion, String createdByName, String createdAt) {

    
    public record Scope(String planName, String rangeLabel, String targetVersion,
                        int caseTotal, int planCount) {
    }

    
    public record Results(int total, int notRun, int running, int passed, int failed, int skipped,
                          double passRate, int unstableSkipped) {
    }

    
    public record DefectDist(int total, int openCount, List<SeverityCount> bySeverity,
                             List<StatusCount> byStatus) {
    }

    
    public record SeverityCount(String label, int count) {
    }

    
    public record StatusCount(String label, int count) {
    }

    
    public record EnvVersion(String envName, String version, int batchCount, int passed, int failed) {
    }

    
    public record Conclusion(boolean pass, String summary) {
    }
}
