package com.sixbean.testpike.aiservice.knowledge.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
@EnableConfigurationProperties(KnowledgeSearchProperties.class)
public class KnowledgeSearchConfig {

 
    @Bean
    public KnowledgeSearchConfigResolver knowledgeSearchConfigResolver(
            CoreGateway coreGateway, KnowledgeSearchProperties properties) {
        return new KnowledgeSearchConfigResolver(coreGateway, properties);
    }
}
