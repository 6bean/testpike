package com.sixbean.testpike.admin.batch.infrastructure.remote.client;

import com.sixbean.testpike.admin.batch.api.dto.BatchCaseView;
import com.sixbean.testpike.admin.batch.api.dto.BatchCreateRequest;
import com.sixbean.testpike.admin.batch.api.dto.BatchFromTemplateRequest;
import com.sixbean.testpike.admin.batch.api.dto.BatchInfoView;
import com.sixbean.testpike.admin.batch.api.dto.BatchInsightView;
import com.sixbean.testpike.admin.batch.api.dto.BatchRecoveryView;
import com.sixbean.testpike.admin.batch.api.dto.BatchReportView;
import com.sixbean.testpike.admin.batch.api.dto.BatchStartResultView;
import com.sixbean.testpike.admin.batch.api.dto.BatchTemplateSaveRequest;
import com.sixbean.testpike.admin.batch.api.dto.BatchTemplateView;
import com.sixbean.testpike.admin.batch.api.dto.BatchUpdateRequest;
import com.sixbean.testpike.admin.batch.api.dto.TemplateScheduleRequest;
import com.sixbean.testpike.admin.project.api.dto.PageView;

import java.util.List;


public interface CoreBatchClient {

    
    BatchInfoView create(BatchCreateRequest request);

    
    BatchInfoView update(long id, BatchUpdateRequest request);

    
    BatchStartResultView start(long id);

    
    BatchInfoView pause(long id, Integer version);

    
    BatchInfoView resume(long id, Integer version);

    
    BatchInfoView cancel(long id, Integer version);

    
    BatchRecoveryView recover(long id);

    
    BatchReportView report(long id);

    /** Failure-insight read door (AO-7 stage 3): core landing row read straight;
     *  honest absence — null data when nothing landed (card hidden, no error). */
    BatchInsightView insight(long id);

    
    String skipCase(long id);

    
    int rerunFailed(long id);

    
    BatchInfoView get(long id);

    /**
     * Cursor window over the process-event ledger of one batch execution
     * (R62 B-face tail door). The row shape is core's single source; the
     * caller's project membership is guarded on the admin bridge before
     * this leg is ever reached.
     */
    java.util.List<com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView> processEvents(
            long id, long cursor, Integer limit);

    /** Batch list (project isolation + filters + paging + whitelisted sorting; optional program lens). */
    PageView<BatchInfoView> list(long projectId, Integer status, String keyword, int page, int size,
                                 String sortBy, String sortDir, long programId);

    
    List<BatchCaseView> listCases(long batchId);

    
    BatchTemplateView saveAsTemplate(long batchId, BatchTemplateSaveRequest request);

    
    List<BatchTemplateView> listTemplates(long projectId);

    
    BatchTemplateView getTemplate(long templateId);

    
    BatchInfoView createFromTemplate(BatchFromTemplateRequest request);

    
    void deleteTemplate(long templateId);

    
    BatchTemplateView updateTemplateSchedule(long templateId, TemplateScheduleRequest request);
}
