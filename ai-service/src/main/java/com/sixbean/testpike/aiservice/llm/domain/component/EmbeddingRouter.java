package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmConfigBrief;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;


@Component
public class EmbeddingRouter {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingRouter.class);

    private final CoreGateway coreGateway;
    private final EmbeddingProperties properties;

    public EmbeddingRouter(CoreGateway coreGateway, EmbeddingProperties properties) {
        this.coreGateway = coreGateway;
        this.properties = properties;
    }

    
    public EmbeddingEndpointConfig route(long projectId) {
        try {
            LlmConfigBrief brief = coreGateway.getProjectEmbeddingConfig(projectId);
            if (brief != null) {
                String source = "project".equals(brief.source())
                        ? EmbeddingEndpointConfig.SOURCE_PROJECT
                        : EmbeddingEndpointConfig.SOURCE_DEFAULT_EMB;
                log.info("Embedding route projectId={} source={} model={} baseUrl={} dimension={}",
                        projectId, source, brief.modelName(), brief.baseUrl(), brief.embeddingDimension());
                return toEndpoint(brief, source);
            }
            log.info("Embedding route projectId={} has no catalog config, falling back to config file source={}",
                    projectId, EmbeddingEndpointConfig.SOURCE_CONFIG);
        } catch (Exception e) {
            log.warn("Embedding route core query failed projectId={} err={}, falling back to config file",
                    projectId, e.getMessage());
        }
        return new EmbeddingEndpointConfig(properties.getProvider(), properties.getModel(),
                properties.getApiKey(), properties.getBaseUrl(),
                EmbeddingEndpointConfig.SOURCE_CONFIG, properties.getDimension(), null);
    }

    
    public List<EmbeddingEndpointConfig> routeChain(long projectId) {
        List<EmbeddingEndpointConfig> chain = new ArrayList<>();
        EmbeddingEndpointConfig primary = route(projectId);
        if (!isBlank(primary.baseUrl())) {
            chain.add(primary);
        }
        for (LlmConfigBrief brief : coreGateway.listEnabledEmbeddingConfigs()) {
            if (isBlank(brief.baseUrl())) {
                continue;
            }
            boolean duplicate = chain.stream().anyMatch(e -> e.baseUrl().equals(brief.baseUrl()));
            if (!duplicate) {
                chain.add(toEndpoint(brief, EmbeddingEndpointConfig.SOURCE_CHAIN));
            }
        }
        return chain;
    }

    
    private static EmbeddingEndpointConfig toEndpoint(LlmConfigBrief brief, String source) {
        return new EmbeddingEndpointConfig(brief.provider(), brief.modelName(), brief.apiKey(),
                brief.baseUrl(), source, brief.embeddingDimension(), brief.id());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
