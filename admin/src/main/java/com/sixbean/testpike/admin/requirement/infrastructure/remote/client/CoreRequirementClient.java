package com.sixbean.testpike.admin.requirement.infrastructure.remote.client;

import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.requirement.api.dto.CaseBriefView;
import com.sixbean.testpike.admin.requirement.api.dto.RequirementVersionView;
import com.sixbean.testpike.admin.requirement.api.dto.CreateRequirementRequest;
import com.sixbean.testpike.admin.requirement.api.dto.LinkResultView;
import com.sixbean.testpike.admin.requirement.api.dto.RequirementView;
import com.sixbean.testpike.admin.requirement.api.dto.TraceChainView;
import com.sixbean.testpike.admin.requirement.api.dto.UpdateRequirementRequest;

import java.util.List;


public interface CoreRequirementClient {

    /** Case-gap page (requirements with no covering live case; caller enforces
     *  view access first; a refusal while the switch is off passes through as a
     *  business error naming the settings page and the key). */
    PageView<RequirementView> listUncoveredRequirements(long projectId, int page, int size);

    /** Requirement list (status/keyword/paging; caller enforces H1 project access first). */
    default PageView<RequirementView> listRequirements(long projectId, Integer status, String keyword,
                                                       int page, int size, String sortBy, String sortDir) {
        return listRequirements(projectId, status, keyword, page, size, sortBy, sortDir, null);
    }

    /** Same list with the id allow-list (comma-separated ids; blank/null = no filter). */
    default PageView<RequirementView> listRequirements(long projectId, Integer status, String keyword,
                                                       int page, int size, String sortBy, String sortDir,
                                                       String ids) {
        return listRequirements(projectId, status, keyword, page, size, sortBy, sortDir, ids, null);
    }

    /** segment B: same list with an exact content-fingerprint filter (blank/null = no filter;
 * the core side normalizes the input and matches the stored display form exactly). */
    PageView<RequirementView> listRequirements(long projectId, Integer status, String keyword,
                                               int page, int size, String sortBy, String sortDir,
                                               String ids, String fingerprint);

    
    RequirementView getRequirement(long requirementId);

    
    RequirementView createRequirement(long projectId, CreateRequirementRequest request);

    
    RequirementView updateRequirement(long requirementId, UpdateRequirementRequest request);

    
    long deleteRequirement(long requirementId);

    
    LinkResultView linkCases(long requirementId, List<Long> caseIds);

    
    long unlinkCase(long requirementId, long caseId);

    
    List<RequirementView> listRequirementsByCaseId(long caseId);

    
    List<CaseBriefView> listCasesByRequirementId(long requirementId);

    /** Requirement version chain newest-first (ledger page version dialog read surface). */
    List<RequirementVersionView> listRequirementVersions(long requirementId);

 
    List<Long> offlineCaseIds(long projectId, List<Long> caseIds);

 
    TraceChainView traceChainOfRequirement(long requirementId);

 
    TraceChainView traceChainOfCase(long caseId);
}
