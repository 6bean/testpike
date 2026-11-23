package com.sixbean.testpike.admin.llmconfig.infrastructure.remote.client;

import com.sixbean.testpike.admin.llmconfig.api.dto.GoldenDriftPageView;
import com.sixbean.testpike.admin.llmconfig.api.dto.LlmConfigSaveRequest;
import com.sixbean.testpike.admin.llmconfig.api.dto.LlmConfigView;

import java.util.ArrayList;
import java.util.List;


public class StubLlmConfigClient implements LlmConfigClient {

    private final List<LlmConfigView> configs = new ArrayList<>();
    private ProjectLlmConfigRef projectRef = new ProjectLlmConfigRef(null, null);
    private long nextId = 100;

    public void seed(LlmConfigView view) {
        configs.add(view);
    }

    public void projectRef(ProjectLlmConfigRef ref) {
        this.projectRef = ref;
    }

    @Override
    public List<LlmConfigView> list(Integer modelType) {
        return configs.stream()
                .filter(c -> modelType == null || c.modelType() == modelType)
                .toList();
    }

    @Override
    public LlmConfigView get(long id) {
        return configs.stream().filter(c -> c.id() == id).findFirst().orElse(null);
    }

    @Override
    public LlmConfigView create(LlmConfigSaveRequest request) {
        LlmConfigView created = new LlmConfigView(nextId++, request.name(), request.provider(),
                request.modelName(), request.apiKey() == null ? null : "******",
                request.baseUrl(), request.status(), request.isDefault(), request.description(),
                request.promptLimitTokens() == null ? 1_048_576 : request.promptLimitTokens(),
                request.tokenizerFamily() == null ? "auto" : request.tokenizerFamily(),
                request.modelType() == null ? 1 : request.modelType(),
                request.embeddingDimension());
        configs.add(created);
        return created;
    }

    @Override
    public LlmConfigView update(long id, LlmConfigSaveRequest request) {
        LlmConfigView updated = new LlmConfigView(id, request.name(), request.provider(),
                request.modelName(), "******", request.baseUrl(), request.status(),
                request.isDefault(), request.description(),
                request.promptLimitTokens() == null ? 1_048_576 : request.promptLimitTokens(),
                request.tokenizerFamily() == null ? "auto" : request.tokenizerFamily(),
                request.modelType() == null ? 1 : request.modelType(),
                request.embeddingDimension());
        configs.removeIf(c -> c.id() == id);
        configs.add(updated);
        return updated;
    }

    @Override
    public void delete(long id) {
        configs.removeIf(c -> c.id() == id);
    }

    @Override
    public ProjectLlmConfigRef getProjectConfig(long projectId) {
        return projectRef;
    }

    @Override
    public ProjectLlmConfigRef updateProjectConfig(long projectId, Long llmConfigId) {
        String name = llmConfigId == null ? null : configs.stream()
                .filter(c -> c.id() == llmConfigId).findFirst().map(LlmConfigView::name).orElse(null);
        projectRef = new ProjectLlmConfigRef(llmConfigId, name);
        return projectRef;
    }

    @Override
    public List<LlmConfigView> listEnabled() {
        return configs.stream().filter(c -> c.status() == 1 && c.modelType() == 1).toList();
    }

    private GoldenDriftPageView returnedRoutePage = new GoldenDriftPageView(List.of(), 0L, 1, 50);
    private String lastRouteDomain;
    private String lastRouteControlRef;
    private String lastRouteCandidateRef;
    private int lastRoutePage;
    private int lastRoutePageSize;

    public void returnedRoutePage(GoldenDriftPageView page) {
        this.returnedRoutePage = page;
    }

    public String lastRouteDomain() {
        return lastRouteDomain;
    }

    public String lastRouteControlRef() {
        return lastRouteControlRef;
    }

    public String lastRouteCandidateRef() {
        return lastRouteCandidateRef;
    }

    public int lastRoutePage() {
        return lastRoutePage;
    }

    public int lastRoutePageSize() {
        return lastRoutePageSize;
    }

    @Override
    public GoldenDriftPageView routeReplays(String domain, String controlRef, String candidateRef,
                                            int page, int pageSize) {
        this.lastRouteDomain = domain;
        this.lastRouteControlRef = controlRef;
        this.lastRouteCandidateRef = candidateRef;
        this.lastRoutePage = page;
        this.lastRoutePageSize = pageSize;
        return returnedRoutePage;
    }
}
