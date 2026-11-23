package com.sixbean.testpike.admin.reportcenter.app.service.impl;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterDetailView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterGenerateInput;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterListItemView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterProgramOverviewView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterQueryInput;
import com.sixbean.testpike.admin.reportcenter.app.service.ReportCenterAdminService;
import com.sixbean.testpike.admin.reportcenter.infrastructure.remote.client.CoreReportCenterClient;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;


@Service
public class ReportCenterAdminServiceImpl implements ReportCenterAdminService {

    
    private static final int SCOPE_GLOBAL = 2;
    
    private static final int SCOPE_PROGRAM = 3;

    private final CoreReportCenterClient coreReportCenterClient;
    private final ProjectAccessGuard guard;

    public ReportCenterAdminServiceImpl(CoreReportCenterClient coreReportCenterClient,
                                        ProjectAccessGuard guard) {
        this.coreReportCenterClient = coreReportCenterClient;
        this.guard = guard;
    }

    @Override
    public PageView<ReportCenterListItemView> list(CurrentUser user, ReportCenterQueryInput query) {
        if (query.scopeId() == null && query.programId() <= 0) {
            requireAdmin(user, "跨项目报表列表仅系统管理员可查看");
        } else if (query.scopeId() != null) {
            guard.assertCanView(user, query.scopeId());
        }
        // A program lens without a single project skips the per-project guard: core
        // narrows the segment to lens ∩ the caller's visibility (operator rides the
        // X-Operator-Id header) — same posture as the batch/defect/API/perf bridges.
        return coreReportCenterClient.list(user.userId(), query);
    }

    @Override
    public ReportCenterProgramOverviewView programOverview(CurrentUser user, long programId,
            String from, String to) {
        // No per-project guard here on purpose: core slices the feed to lens projects
        // intersected with the caller's visibility (outsider = empty feed, missing
        // program = 40401), a duplicate decision on this side would drift from it.
        return coreReportCenterClient.programOverview(user.userId(), programId, from, to);
    }

    @Override
    public ReportCenterDetailView detail(CurrentUser user, long id) {
        ReportCenterDetailView view = coreReportCenterClient.detail(id);
        assertCanViewRow(user, view);
        return view;
    }

    @Override
    public ReportCenterDetailView generate(CurrentUser user, ReportCenterGenerateInput input) {
        requireAdmin(user, "报表手动补生成仅系统管理员可操作");
        return coreReportCenterClient.generate(user.userId(), input);
    }

    @Override
    public ResponseEntity<byte[]> export(CurrentUser user, long id, String format, String theme) {
        ReportCenterDetailView view = coreReportCenterClient.detail(id);
        assertCanViewRow(user, view);
        return coreReportCenterClient.export(user.userId(), id, format, theme);
    }

    
    private void assertCanViewRow(CurrentUser user, ReportCenterDetailView view) {
        if (view.scopeType() != null && view.scopeType() == SCOPE_GLOBAL) {
            requireAdmin(user, "全局维报表仅系统管理员可查看");
            return;
        }
        if (view.scopeType() != null && view.scopeType() == SCOPE_PROGRAM) {
            requireAdmin(user, "项目集维报表仅系统管理员可查看");
            return;
        }
        if (view.scopeId() != null) {
            guard.assertCanView(user, view.scopeId());
        }
    }

    private void requireAdmin(CurrentUser user, String message) {
        if (!guard.isAdmin(user)) {
            throw new ServiceException(message, BizErrorCode.FORBIDDEN);
        }
    }
}
