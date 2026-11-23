package com.sixbean.testpike.aiservice.aichat.infrastructure.config;

import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxConfigResolver;
import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxProperties;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring registration of the ai.ctx.* long-context resolver
 * contract ③). Before this door both {@link AiCtxConfigResolver} and
 * {@link AiCtxProperties} existed as plain classes with zero @Bean, so no
 * component could inject the hot-config reader. Shape follows
 * {@code KnowledgeSearchConfig}: the properties tier is enabled for relaxed
 * binding, the resolver is assembled explicitly (it takes the CoreGateway
 * constructor argument, which no scan would wire).
 */
@Configuration
@EnableConfigurationProperties(AiCtxProperties.class)
public class AiCtxConfig {

    /** One resolver per application: a fresh K8 read per call, no caching here. */
    @Bean
    public AiCtxConfigResolver aiCtxConfigResolver(CoreGateway coreGateway,
                                                   AiCtxProperties properties) {
        return new AiCtxConfigResolver(coreGateway, properties);
    }

    /** Rolling dialog summariser door : one compressor per app. */
    @Bean
    public com.sixbean.testpike.aiservice.aichat.domain.component.DialogRollingSummary
            dialogRollingSummary(
            com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain chain,
            com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway coreGateway) {
        return new com.sixbean.testpike.aiservice.aichat.domain.component.DialogRollingSummary(chain, coreGateway);
    }
}
