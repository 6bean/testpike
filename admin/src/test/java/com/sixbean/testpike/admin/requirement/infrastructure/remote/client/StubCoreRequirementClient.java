package com.sixbean.testpike.admin.requirement.infrastructure.remote.client;

import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.requirement.api.dto.CaseBriefView;
import com.sixbean.testpike.admin.requirement.api.dto.CreateRequirementRequest;
import com.sixbean.testpike.admin.requirement.api.dto.LinkResultView;
import com.sixbean.testpike.admin.requirement.api.dto.RequirementView;
import com.sixbean.testpike.admin.requirement.api.dto.RequirementVersionView;
import com.sixbean.testpike.admin.requirement.api.dto.TraceChainView;
import com.sixbean.testpike.admin.requirement.api.dto.UpdateRequirementRequest;

import java.util.ArrayList;
import java.util.List;


public class StubCoreRequirementClient implements CoreRequirementClient {

    private final List<RequirementView> requirementsByCase = new ArrayList<>();

 
    private volatile List<Long> offlineHitIds = List.of();

    
    private volatile long[] lastUnlink;

    public void withLinkedRequirements(RequirementView... requirements) {
        requirementsByCase.clear();
        List.of(requirements).forEach(requirementsByCase::add);
    }

    public void withOfflineCaseIds(Long... caseIds) {
        this.offlineHitIds = List.of(caseIds);
    }

    public long[] lastUnlink() {
        return lastUnlink;
    }

    
    public static RequirementView reqView(long id, long projectId, int status) {
        return new RequirementView(id, projectId, 3, "R-" + id, "需求点" + id, null,
                status, "3.1", 1L, null, null, 1, null, null);
    }

    @Override
    public PageView<RequirementView> listRequirements(long projectId, Integer status, String keyword,
                                                      int page, int size, String sortBy, String sortDir,
                                                      String ids, String fingerprint) {
        throw new UnsupportedOperationException("单测不触发需求点列表查询");
    }

    @Override
    public PageView<RequirementView> listUncoveredRequirements(long projectId, int page, int size) {
        throw new UnsupportedOperationException("单测不触发无用例需求点查询");
    }

    @Override
    public RequirementView getRequirement(long requirementId) {
        throw new UnsupportedOperationException("单测不触发需求点详情查询");
    }

    @Override
    public RequirementView createRequirement(long projectId, CreateRequirementRequest request) {
        throw new UnsupportedOperationException("单测不触发需求点创建");
    }

    @Override
    public RequirementView updateRequirement(long requirementId, UpdateRequirementRequest request) {
        throw new UnsupportedOperationException("单测不触发需求点更新");
    }

    @Override
    public long deleteRequirement(long requirementId) {
        throw new UnsupportedOperationException("单测不触发需求点删除");
    }

    @Override
    public LinkResultView linkCases(long requirementId, List<Long> caseIds) {
        throw new UnsupportedOperationException("单测不触发需求点侧关联");
    }

    @Override
    public long unlinkCase(long requirementId, long caseId) {
        this.lastUnlink = new long[] {requirementId, caseId};
        return requirementId;
    }

    @Override
    public List<RequirementView> listRequirementsByCaseId(long caseId) {
        return List.copyOf(requirementsByCase);
    }

    @Override
    public List<CaseBriefView> listCasesByRequirementId(long requirementId) {
        throw new UnsupportedOperationException("单测不触发需求点已关联用例查询");
    }

    @Override
    public List<RequirementVersionView> listRequirementVersions(long requirementId) {
        throw new UnsupportedOperationException("unit tests do not exercise the requirement version chain read");
    }

    @Override
    public List<Long> offlineCaseIds(long projectId, List<Long> caseIds) {
        return caseIds.stream().filter(offlineHitIds::contains).toList();
    }

    @Override
    public TraceChainView traceChainOfRequirement(long requirementId) {
        throw new UnsupportedOperationException("单测不触发追溯链查询");
    }

    @Override
    public TraceChainView traceChainOfCase(long caseId) {
        throw new UnsupportedOperationException("单测不触发追溯链查询");
    }
}
