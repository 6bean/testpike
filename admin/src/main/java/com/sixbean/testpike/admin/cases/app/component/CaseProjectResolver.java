package com.sixbean.testpike.admin.cases.app.component;

import com.sixbean.testpike.admin.cases.infrastructure.remote.client.CoreCaseClient;
import org.springframework.stereotype.Component;


@Component
public class CaseProjectResolver {

    private final CoreCaseClient coreCaseClient;

    public CaseProjectResolver(CoreCaseClient coreCaseClient) {
        this.coreCaseClient = coreCaseClient;
    }

    
    public long resolveProjectId(long caseId) {
        return coreCaseClient.getCase(caseId).projectId();
    }
}
