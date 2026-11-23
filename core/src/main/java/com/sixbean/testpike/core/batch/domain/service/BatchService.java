package com.sixbean.testpike.core.batch.domain.service;

import com.sixbean.testpike.core.batch.api.dto.BadgeView;
import com.sixbean.testpike.core.batch.api.dto.BatchCaseInfo;
import com.sixbean.testpike.core.batch.api.dto.BatchCreateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchFromTemplateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.batch.api.dto.BatchQuery;
import com.sixbean.testpike.core.batch.api.dto.BatchRecoveryView;
import com.sixbean.testpike.core.batch.api.dto.BatchStartResult;
import com.sixbean.testpike.core.batch.api.dto.BatchTemplateInfo;
import com.sixbean.testpike.core.batch.api.dto.BatchUpdateInput;
import com.sixbean.testpike.core.batch.api.dto.EnvOverrideInput;
import com.sixbean.testpike.core.batch.api.dto.TemplateScheduleInput;
import com.sixbean.testpike.core.common.clientobject.PageResult;

import java.util.List;


public interface BatchService {

    
    BatchInfo create(long operatorId, BatchCreateInput input);

    
    BatchInfo update(long operatorId, long id, BatchUpdateInput input);

    
    BatchInfo update(long operatorId, long id, BatchUpdateInput input, int expectedVersion);

    
    BatchInfo get(long id);

    
    default PageResult<BatchInfo> list(BatchQuery query) {
        return list(query, null);
    }

    /**
     * Batch list with an optional program lens.
     *
     * @param operatorId caller used by ProgramLensScope to intersect the lens with the
     *                   visible domain (null = trusted internal call, no intersection).
     *                   Ignored when the query carries no lens (legacy behaviour kept).
     */
    PageResult<BatchInfo> list(BatchQuery query, Long operatorId);

    
    List<BatchCaseInfo> listCases(long batchId);

    
    BatchStartResult start(long operatorId, long id);

    
    BatchStartResult start(long operatorId, long id, EnvOverrideInput envOverrides);

    
    BadgeView getByBadgeToken(String badgeToken);

    
    BadgeView badgeByBatchId(long batchId);

    
    com.sixbean.testpike.core.batch.api.dto.CiVerdictView verdict(long batchId);

    
    BatchStartResult quickExecute(long operatorId, long caseId, Long envId);

    
    BatchStartResult quickExecute(long operatorId, long caseId, Long envId, String browser);

    
    BatchInfo pause(long operatorId, long id);

    
    BatchInfo pause(long operatorId, long id, int expectedVersion);

    
    BatchInfo resume(long operatorId, long id);

    
    BatchInfo resume(long operatorId, long id, int expectedVersion);

    
    BatchInfo cancel(long operatorId, long id);

    
    BatchInfo cancel(long operatorId, long id, int expectedVersion);

    
    String skipCase(long operatorId, long id);

    
    int rerunFailed(long operatorId, long id);

    
    BatchTemplateInfo saveAsTemplate(long operatorId, long batchId, String templateName);

    
    List<BatchTemplateInfo> listTemplates(long projectId);

    
    BatchTemplateInfo getTemplate(long templateId);

    
    BatchTemplateInfo updateTemplateSchedule(long operatorId, long templateId,
                                             TemplateScheduleInput input);

    
    BatchInfo createFromTemplate(long operatorId, BatchFromTemplateInput input);

    
    void deleteTemplate(long operatorId, long templateId);

    
    int sweepWatchdogTimeoutBatches();

    
    BatchRecoveryView recover(long operatorId, long batchId);

    
    com.sixbean.testpike.core.batch.api.dto.BatchReportView report(long batchId);
}
