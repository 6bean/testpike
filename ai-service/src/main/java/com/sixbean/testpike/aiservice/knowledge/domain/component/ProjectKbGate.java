package com.sixbean.testpike.aiservice.knowledge.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;


@Component
public class ProjectKbGate {

    private static final Logger log = LoggerFactory.getLogger(ProjectKbGate.class);

    private final CoreGateway coreGateway;

    public ProjectKbGate(CoreGateway coreGateway) {
        this.coreGateway = coreGateway;
    }

    
    public boolean isAutoIngestEnabled(long projectId) {
        CoreGateway.ProjectBrief brief = coreGateway.getProject(projectId);
        boolean enabled = brief == null || brief.kbEnabled() == null || brief.kbEnabled() == 1;
        if (!enabled) {
            log.info("G1 知识库开关=关 projectId={} → 自动入库豁免（v2.36）", projectId);
        }
        return enabled;
    }
}
