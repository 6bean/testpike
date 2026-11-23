package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.OptimisticLockingException;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.cases.api.dto.AssignBatchResult;
import com.sixbean.testpike.core.cases.api.dto.AssignResult;
import com.sixbean.testpike.core.cases.api.dto.BatchEditRequest;
import com.sixbean.testpike.core.cases.api.dto.BatchEditResult;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseExportRequest;
import com.sixbean.testpike.core.cases.api.dto.CaseExportResult;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseQuery;
import com.sixbean.testpike.core.cases.api.dto.CaseRecycleInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseReviewLogInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseRollbackResult;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.api.dto.CaseUpdateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseVersionDiff;
import com.sixbean.testpike.core.cases.api.dto.CaseVersionInfo;
import com.sixbean.testpike.core.cases.api.dto.FlakyDetectionResult;
import com.sixbean.testpike.core.cases.api.dto.ReviewBatchRequest;
import com.sixbean.testpike.core.cases.api.dto.ReviewBatchResult;
import com.sixbean.testpike.core.cases.api.dto.ReviewResult;
import com.sixbean.testpike.core.cases.domain.component.CaseStatusMachine;
import com.sixbean.testpike.core.cases.domain.component.ReviewAction;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public interface CaseService {
    public CaseInfo create(Long operatorId, CaseCreateInput input);

    public CaseInfo get(long id);

    
    public default PageResult<CaseInfo> list(CaseQuery query) {
        return list(query, null);
    }

    
    public PageResult<CaseInfo> list(CaseQuery query, Long operatorId);

    public CaseInfo update(Long operatorId, long id, CaseUpdateInput input, int expectedVersion);

    
    public List<String> checkReferences(long caseId);

    
    public List<CaseReviewLogInfo> listReviewLogs(long caseId);

    public long delete(Long operatorId, long id);

    
    CaseExportResult export(Long operatorId, CaseExportRequest request);

    
    PageResult<CaseRecycleInfo> recycleBin(long projectId, String keyword, int page, int size);

    
    CaseRecycleInfo recycleEntry(long id);

    
    CaseInfo restore(Long operatorId, long id, int expectedVersion);

    
    long purge(Long operatorId, long id);

    
    long clearRecycleBin(Long operatorId, long projectId);

    public ReviewResult review(Long operatorId, long id, ReviewAction action, String remark, int expectedVersion);

    
    public ReviewResult review(Long operatorId, long id, ReviewAction action, String remark,
                               int expectedVersion, java.util.List<String> feedbackTypes);

    
    public ReviewBatchResult reviewBatch(Long operatorId, ReviewBatchRequest request);

    public AssignResult assign(Long operatorId, long caseId, Long assigneeId);

    public AssignBatchResult assignBatch(Long operatorId, long projectId, List<Long> caseIds, Long assigneeId);

    
    public BatchEditResult batchEdit(Long operatorId, BatchEditRequest request);

    
    public List<CaseInfo> listByIds(List<Long> ids);

    
    public List<CaseVersionInfo> listVersions(long caseId);

    
    public CaseVersionDiff diffVersion(long caseId, int version, Integer baseVersion);

    
    public CaseRollbackResult rollback(Long operatorId, long caseId, int targetVersion, int expectedVersion);

    
    public CaseInfo setFlaky(Long operatorId, long caseId, boolean isolated, String reason);

    
    public CaseInfo markExcellent(Long operatorId, long caseId, boolean excellent, int expectedVersion);

    
    public FlakyDetectionResult detectFlaky();

    
    CaseInfo clone(Long operatorId, long id, Long targetProjectId);

}
