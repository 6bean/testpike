package com.sixbean.testpike.admin.reports.app.service.impl;

import com.sixbean.testpike.admin.audit.api.dto.AccessDeniedAuditRequest;
import com.sixbean.testpike.admin.audit.infrastructure.remote.client.CoreAuditClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.reports.api.dto.CompareItemView;
import com.sixbean.testpike.admin.reports.app.service.ReportCompareService;
import com.sixbean.testpike.admin.reports.infrastructure.remote.client.CoreReportClient;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class ReportCompareServiceImpl implements ReportCompareService {

    private static final Logger log = LoggerFactory.getLogger(ReportCompareServiceImpl.class);

    private final ProjectAccessGuard guard;
    private final CoreReportClient coreReportClient;
    private final CoreAuditClient coreAuditClient;

    public ReportCompareServiceImpl(ProjectAccessGuard guard, CoreReportClient coreReportClient,
                                    CoreAuditClient coreAuditClient) {
        this.guard = guard;
        this.coreReportClient = coreReportClient;
        this.coreAuditClient = coreAuditClient;
    }

    @Override
    public List<CompareItemView> compare(CurrentUser user, List<Long> projectIds) {
        com.sixbean.testpike.common.util.CompareIdsValidator.validate(projectIds);
        for (Long pid : projectIds) {
            if (!canView(user, pid)) {
                deny(user, projectIds, pid);
            }
        }
        return coreReportClient.compare(projectIds);
    }

    
    private boolean canView(CurrentUser user, long projectId) {
        try {
            guard.assertCanView(user, projectId);
            return true;
        } catch (ServiceException e) {
            return false;
        }
    }

 
    private void deny(CurrentUser user, List<Long> attempted, long deniedProjectId) {
        try {
            coreAuditClient.writeAccessDenied(
                    AccessDeniedAuditRequest.compareDenied(user.userId(), attempted, deniedProjectId));
        } catch (Exception e) {
            log.warn("越权审计写入失败（不阻断 403 判定）user={} denied={} err={}",
                    user.userId(), deniedProjectId, e.getMessage());
        }
        throw new ServiceException("对比项目包含您无权访问的项目（项目 ID: " + deniedProjectId
                + "），已整体拒绝", BizErrorCode.FORBIDDEN);
    }
}
