package com.sixbean.testpike.aiservice.mcp.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class McpComponentConfig {

    
    @Bean
    public McpSessionFactory mcpSessionFactory(ObjectMapper objectMapper) {
        return new McpHttpSessionFactory(objectMapper);
    }
}
