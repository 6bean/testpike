package com.sixbean.testpike.api.mcp.api;

import com.sixbean.testpike.api.mcp.domain.component.McpBearerAuthenticator;
import com.sixbean.testpike.api.mcp.domain.component.McpContextKeys;
import com.sixbean.testpike.api.mcp.domain.component.McpInvocationRecorder;
import com.sixbean.testpike.api.mcp.domain.component.McpPrincipal;
import com.sixbean.testpike.api.mcp.domain.component.McpDailyQuotaGuard;
import com.sixbean.testpike.api.mcp.domain.component.McpRateLimiter;
import com.sixbean.testpike.api.mcp.domain.component.McpServerHandler;
import com.sixbean.testpike.api.mcp.domain.component.McpToolCatalog;
import com.sixbean.testpike.api.mcp.domain.component.McpPassThroughToolRegistry;
import com.sixbean.testpike.api.mcp.domain.component.McpToolVisibilityFilter;
import com.sixbean.testpike.api.mcp.domain.component.McpTransportAuthValidator;
import com.sixbean.testpike.api.mcp.infrastructure.remote.ApiTokenVerifyClient;
import com.sixbean.testpike.api.mcp.infrastructure.remote.McpToolGateway;
import com.sixbean.testpike.api.mcp.infrastructure.remote.RestCoreConfigClient;
import com.sixbean.testpike.api.mcp.infrastructure.remote.RestToolInvocationRecordClient;
import com.sixbean.testpike.api.mcp.infrastructure.remote.ToolInvocationRecordClient;
import com.sixbean.testpike.common.idempotent.IdempotentGuard;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;


@Configuration
public class McpServerConfig {

    
    static final String MCP_ENDPOINT = "/mcp";

    @Bean
    public McpToolCatalog mcpToolCatalog() {
        return new McpToolCatalog();
    }

    @Bean
    public McpToolVisibilityFilter mcpToolVisibilityFilter(McpToolCatalog catalog) {
        return new McpToolVisibilityFilter(catalog);
    }

    @Bean
    public McpBearerAuthenticator mcpBearerAuthenticator(ApiTokenVerifyClient verifyClient) {
        return new McpBearerAuthenticator(verifyClient);
    }

 
    @Bean
    public McpRateLimiter mcpRateLimiter(org.springframework.data.redis.core.StringRedisTemplate redis,
                                         RestCoreConfigClient configClient) {
        return new McpRateLimiter(redis, configClient.rateLimitPerMinute(),
                configClient.secondsSupplier(McpRateLimiter.WINDOW_KEY,
                        McpRateLimiter.DEFAULT_WINDOW_SECONDS));
    }

 /** Daily quota gate: cap = mcp.quota.calls-per-token-day live read (hot-updatable),
     *  day window keyed by token fingerprint, counting via the shared fixed-window counter. */
    @Bean
    public McpDailyQuotaGuard mcpDailyQuotaGuard(
            org.springframework.data.redis.core.StringRedisTemplate redis,
            RestCoreConfigClient configClient) {
        return new McpDailyQuotaGuard(redis, configClient.dailyQuotaCallsPerTokenDay());
    }

    @Bean
    public McpTransportAuthValidator mcpTransportAuthValidator(McpBearerAuthenticator authenticator,
                                                               McpRateLimiter rateLimiter,
                                                               McpDailyQuotaGuard dailyQuotaGuard) {
        return new McpTransportAuthValidator(authenticator, rateLimiter::tryAcquire,
                dailyQuotaGuard::tryAcquire);
    }

    
    @Bean
    public WebMvcStatelessServerTransport mcpStatelessTransport(McpTransportAuthValidator validator) {
        return WebMvcStatelessServerTransport.builder()
                .jsonMapper(McpJsonDefaults.getMapper())
                .messageEndpoint(MCP_ENDPOINT)
                .securityValidator(validator)
                .contextExtractor(request -> transportContext(request, validator))
                .build();
    }

    
    @Bean
    public ToolInvocationRecordClient toolInvocationRecordClient(
            @org.springframework.beans.factory.annotation.Value("${core.base-url:http://localhost:8083}")
            String coreBaseUrl) {
        return new McpInvocationRecorder(new RestToolInvocationRecordClient(coreBaseUrl));
    }

    
    @Bean
    public McpServerHandler mcpServerHandler(WebMvcStatelessServerTransport transport,
                                             McpToolCatalog catalog,
                                             McpToolVisibilityFilter visibilityFilter,
                                             McpPassThroughToolRegistry passThroughRegistry,
                                             McpToolGateway gateway,
                                             RestCoreConfigClient configClient,
                                             IdempotentGuard idempotentGuard,
                                             ToolInvocationRecordClient recordClient) {
        McpServerHandler handler = new McpServerHandler(catalog, visibilityFilter,
                passThroughRegistry,
                McpJsonDefaults.getMapper(), McpJsonDefaults.getSchemaValidator(),
                configClient.readonlyFlag(), gateway, idempotentGuard, recordClient);
        transport.setMcpHandler(handler);
        return handler;
    }

    
    @Bean
    public RouterFunction<ServerResponse> mcpRouterFunction(WebMvcStatelessServerTransport transport) {
        return transport.getRouterFunction();
    }

    private static McpTransportContext transportContext(
            org.springframework.web.servlet.function.ServerRequest request,
            McpTransportAuthValidator validator) {
        Map<String, Object> metadata = new HashMap<>();
        Optional<McpPrincipal> principal =
                validator.cachedPrincipal(request.headers().firstHeader("Authorization"));
        principal.ifPresent(p -> metadata.put(McpContextKeys.PRINCIPAL, p));
        String idempotencyKey = request.headers().firstHeader("Idempotency-Key");
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            metadata.put(McpContextKeys.IDEMPOTENCY_KEY, idempotencyKey);
        }
        return McpTransportContext.create(metadata);
    }
}
