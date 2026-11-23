package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmConfigBrief;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;


@Component
public class ProjectLlmRouter {

    private static final Logger log = LoggerFactory.getLogger(ProjectLlmRouter.class);

 
    private static final String KEY_PROVIDER = "llm.provider";
    private static final String KEY_BASE_URL = "llm.base-url";
    private static final String KEY_MODEL = "llm.model";
    private static final String KEY_API_KEY = "llm.api-key";

    private final CoreGateway coreGateway;
    private final LlmProperties properties;

    public ProjectLlmRouter(CoreGateway coreGateway, LlmProperties properties) {
        this.coreGateway = coreGateway;
        this.properties = properties;
    }

    
    public LlmEndpointConfig route(long projectId) {
        try {
            LlmConfigBrief brief = coreGateway.getProjectLlmConfig(projectId);
            if (brief != null) {
                String source = "project".equals(brief.source())
                        ? LlmEndpointConfig.SOURCE_PROJECT : LlmEndpointConfig.SOURCE_DEFAULT;
                log.info("LLM 路由 projectId={} source={} model={} baseUrl={}",
                        projectId, source, brief.modelName(), brief.baseUrl());
                return new LlmEndpointConfig(brief.provider(), brief.modelName(), brief.apiKey(),
                        brief.baseUrl(), source, brief.promptLimitTokens(), brief.id(),
                        brief.tokenizerFamily(), brief.cacheHint(), brief.structuredOutput());
            }
            LlmEndpointConfig fromConfigDb = routeFromConfigDb();
            if (fromConfigDb != null) {
                return fromConfigDb;
            }
            log.info("LLM 路由 projectId={} 无清单且无 K8 配置，回退配置文件 source={}；"
                            + "如需停止回退：在「系统管理-大模型配置」维护模型清单，"
                            + "或在「系统管理-系统配置」配置默认端点（键 llm.provider/llm.base-url/llm.model）",
                    projectId, LlmEndpointConfig.SOURCE_CONFIG);
        } catch (Exception e) {
            log.warn("LLM 路由 core 查询失败 projectId={} err={}，回退配置文件", projectId, e.getMessage());
        }
        return new LlmEndpointConfig(properties.getProvider(), properties.getModel(),
                properties.getApiKey(), properties.getBaseUrl(), LlmEndpointConfig.SOURCE_CONFIG);
    }

    
    private LlmEndpointConfig routeFromConfigDb() {
        String provider = coreGateway.getSysConfig(KEY_PROVIDER);
        String baseUrl = coreGateway.getSysConfig(KEY_BASE_URL);
        String model = coreGateway.getSysConfig(KEY_MODEL);
        if (isBlank(provider) && isBlank(baseUrl) && isBlank(model)) {
            return null;
        }
        String apiKey = coreGateway.getSysConfig(KEY_API_KEY);
        if (isBlank(apiKey)) {
            apiKey = properties.getApiKey();
        }
        log.info("LLM 路由 K8 系统配置表默认端点 source={} model={} baseUrl={}",
                LlmEndpointConfig.SOURCE_CONFIG_DB, model, baseUrl);
        return new LlmEndpointConfig(provider, model, apiKey, baseUrl, LlmEndpointConfig.SOURCE_CONFIG_DB);
    }

    
    public List<LlmEndpointConfig> routeChain(long projectId) {
        List<LlmEndpointConfig> chain = new ArrayList<>();
        LlmEndpointConfig primary = route(projectId);
        if (!isBlank(primary.baseUrl())) {
            chain.add(primary);
        }
        for (var brief : coreGateway.listEnabledLlmConfigs()) {
            if (isBlank(brief.baseUrl())) {
                continue;
            }
            boolean duplicate = chain.stream().anyMatch(e -> e.baseUrl().equals(brief.baseUrl()));
            if (!duplicate) {
                chain.add(new LlmEndpointConfig(brief.provider(), brief.modelName(),
                        brief.apiKey(), brief.baseUrl(), LlmEndpointConfig.SOURCE_CHAIN,
                        brief.promptLimitTokens(), brief.id(), brief.tokenizerFamily(),
                        brief.cacheHint(), brief.structuredOutput()));
            }
        }
        return chain;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
