package com.sixbean.testpike.core.requirement.domain.service;

import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.api.dto.RequirementInfo;
import com.sixbean.testpike.core.requirement.api.dto.RequirementReviewAction;
import com.sixbean.testpike.core.requirement.api.dto.RequirementLinkResult;
import com.sixbean.testpike.core.requirement.api.dto.RequirementQuery;
import com.sixbean.testpike.core.requirement.api.dto.RequirementUpdateInput;
import com.sixbean.testpike.core.requirement.api.dto.RequirementVersionInfo;

import java.util.List;

public interface RequirementService {

    /** K8 switch (V378, factory default on): gates the server-computed
     * case-gap list. While off, the query refuses with a pointer naming the
     * settings page and this key (AI-feature honesty rule). */
    String KEY_CASE_GAP_SUGGEST = "ai.doc.case-gap-suggest";

 
    RequirementInfo create(Long operatorId, RequirementCreateInput input);

    
    RequirementInfo get(long id);

    
    PageResult<RequirementInfo> list(RequirementQuery query);

    /**
     * Case-gap list for the generation pipeline: newest-first page of this
     * project's requirements that no active case covers (one-click handoff
     * pre-fills the existing batch-creation entry; human gates unchanged).
     * <p>Scope is pinned to the coverage-matrix semantics (see
     * CoverageMatrixServiceImpl — single source of the covered wording):
     * retired requirements (status = 3) stay out, and a link counts only when
     * the case row is alive (deleted = 0) and not archived (status &lt;&gt; 9).
     * <p>Refuses with a settings pointer while ai.doc.case-gap-suggest is off.
     */
    PageResult<RequirementInfo> listUncovered(long projectId, int page, int size);

    
    RequirementInfo update(Long operatorId, long id, RequirementUpdateInput input);

    /**
     * Explicit submit-review door (batched review: conversation or editor
     * accepts only roll versions; the review state moves on this call alone).
     * PASS flips a changed row back to active, ledger-records the exact version
     * it was granted on and opens the knowledge-base ingest path; a PASS replay
     * on the still-current version writes nothing. NEED_CHANGE/REJECT require a
     * remark and open no ingest path.
     */
    RequirementInfo submitReview(Long operatorId, long id, RequirementReviewAction action,
                                 String remark);

    
    long delete(Long operatorId, long id);

    
    RequirementLinkResult linkCases(Long operatorId, long requirementId, List<Long> caseIds);

    
    long unlinkCase(Long operatorId, long requirementId, long caseId);

    
    List<RequirementInfo> listByCaseId(long caseId);

    
    List<CaseInfo> listCasesByRequirementId(long requirementId);

    
    List<Long> offlineCaseIds(long projectId, List<Long> caseIds);

    /**
     * Version chain newest-first for the ledger page version dialog
     * (GET /internal/requirements/{id}/versions). Unknown or soft-deleted
     * requirements reuse the detail 40401 wording; every snapshot row carries
     * operator display name and per-version changed-field count, corrupted
     * snapshots are marked instead of throwing.
     */
    List<RequirementVersionInfo> listVersions(long id);
}
