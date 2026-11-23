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
import com.sixbean.testpike.admin.cases.api.dto.ReviewLockView;
import com.sixbean.testpike.admin.cases.api.dto.ReviewResultView;
import com.sixbean.testpike.admin.cases.api.dto.UpdateCaseRequest;
import com.sixbean.testpike.admin.project.api.dto.PageView;



import java.util.List;


public interface CoreCaseClient {

    /**
 * Pending flaky restore proposals of a project (core GET /internal/cases/flaky-proposals,
 * AO batch read channel). Zero-write; the view permission is checked by
 * the admin caller before this client is used.
 */
    List<com.sixbean.testpike.admin.cases.api.dto.FlakyRestoreProposalView> flakyRestoreProposals(
            long projectId);

    
    PageView<CaseView> listCases(long projectId, Integer status, String keyword, Integer priority,
                                 Integer confidence, Long assigneeId, Integer phase, List<Long> tagIds,
                                 String sortBy, String sortDir, int page, int size,
                                 Boolean excellentOnly, long programId,
                                 String sourceFacet, Integer evidenceGrade);

    
    CaseView getCase(long caseId);

    
    CaseView createCase(CreateCaseRequest request);

    
    CaseView updateCase(long caseId, UpdateCaseRequest request);

    
    ReviewResultView reviewCase(long caseId, ReviewCaseRequest request);

 
    ReviewLockView acquireReviewLock(long caseId);

 
    ReviewLockView heartbeatReviewLock(long caseId);

    
    void releaseReviewLock(long caseId);

    
    BatchReviewResultView batchReviewCases(long projectId, String action, String remark,
                                           List<Long> caseIds, Boolean confirmLowConfidence,
                                           List<String> feedbackTypes);

    
    AssignResultView assignCase(long caseId, Long assigneeId);

    
    AssignBatchResultView batchAssignCases(long projectId, List<Long> caseIds, Long assigneeId);

    
    AutoAssignResultView autoAssign(long projectId, List<Long> caseIds, boolean preview);

 
    BatchEditResultView batchEditCases(long projectId, List<Long> caseIds,
                                       Integer phase, Integer priority, List<Long> tagIds);

    
    long deleteCase(long caseId);

    
    BatchStartResultView quickExecute(long caseId, Long envId, String browser);

    
    CaseView cloneCase(long caseId, Long targetProjectId);

 
    List<CaseReviewLogView> listReviewLogs(long caseId);

 
    List<CaseVersionView> listVersions(long caseId);

    
    CaseVersionDiffView diffVersion(long caseId, int version, Integer baseVersion);

    
    CaseRollbackResultView rollback(long caseId, int targetVersion, Integer version);

 
    CaseView setFlaky(long caseId, boolean isolated, String reason);

 
    CaseView setExcellent(long caseId, boolean excellent, int version);

 
    List<String> checkReferences(long caseId);

 
    PageView<CaseRecycleView> listRecycleBin(long projectId, String keyword, int page, int size);

    
    CaseRecycleView recycleEntry(long caseId);

    
    CaseView restoreCase(long caseId, int version);

    
    long purgeCase(long caseId);

    
    long clearRecycleBin(long projectId);

 
    CaseExportResultView exportCases(ExportCasesRequest request);
}
