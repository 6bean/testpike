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

import java.util.ArrayList;
import java.util.List;


public class StubCoreReportClient implements CoreReportClient {

    
    public final List<Long> requested = new ArrayList<>();

    @Override
    public List<CompareItemView> compare(List<Long> projectIds) {
        requested.addAll(projectIds);
        return List.of();
    }

    @Override
    public PersonalReviewView personalReview(long projectId, Long userId) {
        throw new UnsupportedOperationException("守卫单测不触发");
    }

    @Override
    public List<TeamMemberStatView> teamBoard(long projectId) {
        throw new UnsupportedOperationException("守卫单测不触发");
    }

    @Override
    public AiQualityView aiQuality(long projectId) {
        throw new UnsupportedOperationException("守卫单测不触发");
    }

    @Override
    public CoverageView coverage(long projectId) {
        throw new UnsupportedOperationException("守卫单测不触发");
    }

    @Override
    public ExecutionStatView executionStat(long projectId) {
        throw new UnsupportedOperationException("守卫单测不触发");
    }

    @Override
    public ExecutionStatView executionStat(long projectId, Long planId) {
        throw new UnsupportedOperationException("守卫单测不触发");
    }

    @Override
    public List<PhaseCount> phaseDist(long projectId) {
        throw new UnsupportedOperationException("守卫单测不触发");
    }

    @Override
    public ExportResultView export(ReportExportRequest request) {
        throw new UnsupportedOperationException("守卫单测不触发");
    }

    @Override
    public List<ExportTaskView> listExportTasks(long projectId) {
        throw new UnsupportedOperationException("守卫单测不触发");
    }

    @Override
    public ExportDownloadView exportTaskDownload(long projectId, long taskId) {
        throw new UnsupportedOperationException("守卫单测不触发");
    }
}
