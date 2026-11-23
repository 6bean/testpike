package com.sixbean.testpike.core.apitest.domain.service;

import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.FlowTemplateInfo;
import com.sixbean.testpike.core.apitest.api.dto.FlowTemplatePageView;
import com.sixbean.testpike.core.apitest.api.dto.FlowTemplateSaveInput;

import java.util.List;

/**
 * Flow-template storage service .
 * <p>A template = a frozen snapshot of one collection's requests (same pinned
 * shape the collection-run ledger uses), reusable across projects: save in
 * project A, instantiate into a collection of project B. Consumption of the
 * snapshot always goes through the request save gate (validation, SSRF,
 * orchestration contract) — templates never write request rows directly.
 */
public interface FlowTemplateService {

    /**
     * Freeze the source collection's members (user order) into a new template.
     * Missing collection 404; empty collection refused with a human message
     * (a snapshot of nothing would instantiate into nothing).
     */
    FlowTemplateInfo save(Long operatorId, FlowTemplateSaveInput input);

    /** Template detail (404 when missing). */
    FlowTemplateInfo get(long templateId);

    /** One paged window of the project's templates, newest first (H1 segment door). */
    FlowTemplatePageView list(long projectId, int page, int pageSize);

    /**
     * Copy every snapshot row into the target collection through the request
     * save gate, in frozen order; returns the created rows. Missing template
     * or target collection 404 (target ownership is guarded admin-side; core
     * guarantees existence only, same posture as the rest of the domain).
     */
    List<ApiRequestInfo> instantiate(Long operatorId, long templateId, long targetCollectionId);

    /** Delete a template (404 when missing; the snapshot is the payload — no cascade). */
    void delete(Long operatorId, long templateId);
}
