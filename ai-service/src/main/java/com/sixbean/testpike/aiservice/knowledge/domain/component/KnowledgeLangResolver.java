package com.sixbean.testpike.aiservice.knowledge.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;


@Component
public class KnowledgeLangResolver {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeLangResolver.class);

    
    public static final String DEFAULT_LANG = "zh-CN";

    private final CoreGateway coreGateway;

    public KnowledgeLangResolver(CoreGateway coreGateway) {
        this.coreGateway = coreGateway;
    }

    
    public String resolve(long projectId) {
        try {
            CoreGateway.ProjectBrief brief = coreGateway.getProject(projectId);
            return brief == null || brief.defaultLang() == null || brief.defaultLang().isBlank()
                    ? DEFAULT_LANG : brief.defaultLang();
        } catch (Exception e) {
            log.warn("G1 项目语言取数失败 projectId={} err={}", projectId, e.getMessage());
            return DEFAULT_LANG;
        }
    }
}
