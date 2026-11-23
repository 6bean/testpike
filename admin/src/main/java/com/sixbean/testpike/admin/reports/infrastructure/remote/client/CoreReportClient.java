package com.sixbean.testpike.admin.reports.infrastructure.remote.client;

import com.sixbean.testpike.admin.reports.api.dto.AiQualityView;
import com.sixbean.testpike.admin.reports.api.dto.CompareItemView;
import com.sixbean.testpike.admin.reports.api.dto.CoverageView;
import com.sixbean.testpike.admin.reports.api.dto.ExecutionStatView;
import com.sixbean.testpike.admin.reports.api.dto.ExportDownloadView;
import com.sixbean.testpike.admin.reports.api.dto.ExportResultView;
import com.sixbean.testpike.admin.reports.api.dto.ExportTaskView;
import com.sixbean.testpike.admin.reports.api.dto.PersonalReviewView;
import com.sixbean.testpike.admin.reports.api.dto.PhaseCount;
import com.sixbean.testpike.admin.reports.api.dto.ReportExportRequest;
import com.sixbean.testpike.admin.reports.api.dto.TeamMemberStatView;

import java.util.List;


public interface CoreReportClient {

    
    PersonalReviewView personalReview(long projectId, Long userId);

    
    List<TeamMemberStatView> teamBoard(long projectId);

    
    AiQualityView aiQuality(long projectId);

    
    CoverageView coverage(long projectId);

    
    ExecutionStatView executionStat(long projectId);

 
    ExecutionStatView executionStat(long projectId, Long planId);

    
    List<PhaseCount> phaseDist(long projectId);

    
    List<CompareItemView> compare(List<Long> projectIds);

    
    ExportResultView export(ReportExportRequest request);

    
    List<ExportTaskView> listExportTasks(long projectId);

    
    ExportDownloadView exportTaskDownload(long projectId, long taskId);
}
