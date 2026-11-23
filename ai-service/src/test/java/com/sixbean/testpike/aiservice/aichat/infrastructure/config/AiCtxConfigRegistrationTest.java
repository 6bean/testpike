package com.sixbean.testpike.aiservice.aichat.infrastructure.config;

import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxConfigResolver;
import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxProperties;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spring registration of the ai.ctx.* resolver :
 * before this door the resolver existed as a plain class with zero @Bean —
 * the dialog assembly could not inject it without the registration. The test
 * pins the wiring behaviour: one config produces a live resolver that reads
 * K8 hot values through CoreGateway and falls back to the file tier on
 * absent / out-of-range values (same ladder as KnowledgeSearchConfig).
 */
class AiCtxConfigRegistrationTest {

    @Test
    void registersResolverReadingK8WithFileTierFallback() {
        CoreGateway gateway = mock(CoreGateway.class);
        when(gateway.getSysConfig(AiCtxConfigResolver.KEY_SESSION_KEEP_RECENT_TURNS))
                .thenReturn("3");
        when(gateway.getSysConfig(AiCtxConfigResolver.KEY_TOOL_BLOCK_MAX_TOKENS))
                .thenReturn("999999");
        AiCtxProperties properties = new AiCtxProperties();

        AiCtxConfigResolver resolver =
                new AiCtxConfig().aiCtxConfigResolver(gateway, properties);

        assertNotNull(resolver);
        assertEquals(3, resolver.sessionKeepRecentTurns());
        assertEquals(properties.getToolBlockMaxTokens(), resolver.toolBlockMaxTokens());
    }

    @Test
    void propertiesClassIsEnabledForRelaxedBinding() {
        assertNotNull(AiCtxConfig.class
                .getAnnotation(EnableConfigurationProperties.class));
    }
}
