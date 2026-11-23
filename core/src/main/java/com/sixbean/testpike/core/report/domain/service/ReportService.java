package com.sixbean.testpike.core.report.domain.service;

import com.sixbean.testpike.core.report.api.dto.AiQualityView;
import com.sixbean.testpike.core.report.api.dto.CompareItemView;
import com.sixbean.testpike.core.report.api.dto.CoverageView;
import com.sixbean.testpike.core.report.api.dto.ExecutionStatView;
import com.sixbean.testpike.core.report.api.dto.ExportDownloadView;
import com.sixbean.testpike.core.report.api.dto.ExportResultView;
import com.sixbean.testpike.core.report.api.dto.ExportTaskView;
import com.sixbean.testpike.core.report.api.dto.PersonalReviewView;
import com.sixbean.testpike.core.report.api.dto.PhaseCount;
import com.sixbean.testpike.core.report.api.dto.TeamMemberStatView;

import java.util.List;


public interface ReportService {

    
    PersonalReviewView personalReview(long projectId, Long userId);

    
    List<TeamMemberStatView> teamBoard(long projectId);

    
    AiQualityView aiQuality(long projectId);

    
    CoverageView coverage(long projectId);

    
    ExecutionStatView executionStat(long projectId);

 
    ExecutionStatView executionStat(long projectId, Long planId);

    
    List<CompareItemView> compare(List<Long> projectIds);

 
    List<PhaseCount> phaseDist(long projectId);

    
    ExportResultView export(long operatorId, long projectId, String reportType, boolean masked);

    
    List<ExportTaskView> listExportTasks(long projectId);

    
    ExportDownloadView exportTaskDownload(long projectId, long taskId);
}
