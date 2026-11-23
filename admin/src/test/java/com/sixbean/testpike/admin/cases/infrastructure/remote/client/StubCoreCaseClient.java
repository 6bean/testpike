package com.sixbean.testpike.admin.cases.infrastructure.remote.client;

import com.sixbean.testpike.admin.batch.api.dto.BatchStartResultView;
import com.sixbean.testpike.admin.cases.api.dto.AssignBatchResultView;
import com.sixbean.testpike.admin.cases.api.dto.AssignResultView;
import com.sixbean.testpike.admin.cases.api.dto.AutoAssignResultView;
import com.sixbean.testpike.admin.cases.api.dto.BatchEditResultView;
import com.sixbean.testpike.admin.cases.api.dto.BatchReviewResultView;
import com.sixbean.testpike.admin.cases.api.dto.CaseExportResultView;
import com.sixbean.testpike.admin.cases.api.dto.CaseReviewLogView;
import com.sixbean.testpike.admin.cases.api.dto.CaseRecycleView;
import com.sixbean.testpike.admin.cases.api.dto.CaseRollbackResultView;
import com.sixbean.testpike.admin.cases.api.dto.CaseVersionDiffView;
import com.sixbean.testpike.admin.cases.api.dto.CaseVersionView;
import com.sixbean.testpike.admin.cases.api.dto.CaseView;
import com.sixbean.testpike.admin.cases.api.dto.CreateCaseRequest;
import com.sixbean.testpike.admin.cases.api.dto.ExportCasesRequest;
import com.sixbean.testpike.admin.cases.api.dto.ReviewCaseRequest;
import com.sixbean.testpike.admin.cases.api.dto.ReviewResultView;
import com.sixbean.testpike.admin.cases.api.dto.UpdateCaseRequest;
import com.sixbean.testpike.admin.project.api.dto.PageView;

import java.util.List;


public class StubCoreCaseClient implements CoreCaseClient {

    private volatile CaseView caseView;

    @Override
    public List<com.sixbean.testpike.admin.cases.api.dto.FlakyRestoreProposalView>
            flakyRestoreProposals(long projectId) {
        throw new UnsupportedOperationException("单测不触发 flaky 恢复提案查询");
    }

    public void withCase(long caseId, long projectId) {
        this.caseView = new CaseView(caseId, "TC-T126-" + caseId, projectId, "守卫测试用例", null, null,
                1, 1, "web", 1, null, 1, null, null, 3, null, 1L, null, null, null, null, 0, null, null,
                null, 0, null, null, null, null);
    }

    @Override
    public PageView<CaseView> listCases(long projectId, Integer status, String keyword, Integer priority,
                                        Integer confidence, Long assigneeId, Integer phase, List<Long> tagIds,
                                        String sortBy, String sortDir, int page, int size,
                                        Boolean excellentOnly, long programId,
                                        String sourceFacet, Integer evidenceGrade) {
        throw new UnsupportedOperationException("单测不触发用例列表查询");
    }

    @Override
    public CaseView getCase(long caseId) {
        CaseView view = caseView;
        if (view == null || view.id() != caseId) {
            throw new com.sixbean.testpike.common.exception.ServiceException(
                    "用例不存在或已被删除", com.sixbean.testpike.common.exception.BizErrorCode.NOT_FOUND);
        }
        return view;
    }

    @Override
    public CaseView createCase(CreateCaseRequest request) {
        throw new UnsupportedOperationException("单测不触发用例创建");
    }

    @Override
    public CaseView updateCase(long caseId, UpdateCaseRequest request) {
        throw new UnsupportedOperationException("单测不触发用例更新");
    }

    @Override
    public ReviewResultView reviewCase(long caseId, ReviewCaseRequest request) {
        throw new UnsupportedOperationException("单测不触发评审");
    }

    @Override
    public com.sixbean.testpike.admin.cases.api.dto.ReviewLockView acquireReviewLock(long caseId) {
        throw new UnsupportedOperationException("单测不触发评审锁（锁语义 core L2+L3 覆盖）");
    }

    @Override
    public com.sixbean.testpike.admin.cases.api.dto.ReviewLockView heartbeatReviewLock(long caseId) {
        throw new UnsupportedOperationException("单测不触发评审锁心跳");
    }

    @Override
    public void releaseReviewLock(long caseId) {
        throw new UnsupportedOperationException("单测不触发评审锁释放");
    }

    @Override
    public BatchReviewResultView batchReviewCases(long projectId, String action, String remark,
                                                  List<Long> caseIds, Boolean confirmLowConfidence,
                                                  List<String> feedbackTypes) {
        throw new UnsupportedOperationException("单测不触发批量评审");
    }

    @Override
    public AssignResultView assignCase(long caseId, Long assigneeId) {
        throw new UnsupportedOperationException("单测不触发分配");
    }

    @Override
    public AssignBatchResultView batchAssignCases(long projectId, List<Long> caseIds, Long assigneeId) {
        throw new UnsupportedOperationException("单测不触发批量分配");
    }

    @Override
    public AutoAssignResultView autoAssign(long projectId, List<Long> caseIds, boolean preview) {
        throw new UnsupportedOperationException("单测不触发自动分配");
    }

    @Override
    public BatchEditResultView batchEditCases(long projectId, List<Long> caseIds,
                                              Integer phase, Integer priority, List<Long> tagIds) {
        throw new UnsupportedOperationException("单测不触发批量编辑");
    }

    @Override
    public long deleteCase(long caseId) {
        throw new UnsupportedOperationException("单测不触发删除");
    }

    @Override
    public BatchStartResultView quickExecute(long caseId, Long envId, String browser) {
        throw new UnsupportedOperationException("单测不触发快速执行");
    }

    @Override
    public CaseView cloneCase(long caseId, Long targetProjectId) {
        throw new UnsupportedOperationException("单测不触发用例克隆");
    }

    @Override
    public List<CaseReviewLogView> listReviewLogs(long caseId) {
        throw new UnsupportedOperationException("单测不触发评审历史查询");
    }

    @Override
    public List<CaseVersionView> listVersions(long caseId) {
        throw new UnsupportedOperationException("单测不触发版本查询");
    }

    @Override
    public CaseVersionDiffView diffVersion(long caseId, int version, Integer baseVersion) {
        throw new UnsupportedOperationException("单测不触发版本 diff");
    }

    @Override
    public CaseRollbackResultView rollback(long caseId, int targetVersion, Integer version) {
        throw new UnsupportedOperationException("单测不触发回滚");
    }

    @Override
    public CaseView setFlaky(long caseId, boolean isolated, String reason) {
        throw new UnsupportedOperationException("单测不触发 flaky 标记");
    }

    @Override
    public CaseView setExcellent(long caseId, boolean excellent, int version) {
        throw new UnsupportedOperationException("单测不触发优秀标记");
    }

    @Override
    public List<String> checkReferences(long caseId) {
        throw new UnsupportedOperationException("单测不触发引用检查");
    }

    @Override
    public PageView<CaseRecycleView> listRecycleBin(long projectId, String keyword, int page, int size) {
        throw new UnsupportedOperationException("单测不触发回收站查询");
    }

    @Override
    public CaseRecycleView recycleEntry(long caseId) {
        throw new UnsupportedOperationException("单测不触发回收站单行查询");
    }

    @Override
    public CaseView restoreCase(long caseId, int version) {
        throw new UnsupportedOperationException("单测不触发还原");
    }

    @Override
    public long purgeCase(long caseId) {
        throw new UnsupportedOperationException("单测不触发彻底删除");
    }

    @Override
    public long clearRecycleBin(long projectId) {
        throw new UnsupportedOperationException("单测不触发清空回收站");
    }

    @Override
    public CaseExportResultView exportCases(ExportCasesRequest request) {
        throw new UnsupportedOperationException("单测不触发导出");
    }
}
