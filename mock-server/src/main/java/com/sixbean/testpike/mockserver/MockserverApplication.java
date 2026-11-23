package com.sixbean.testpike.mockserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.log.ShutdownLoggingPrewarm;
import com.sixbean.testpike.common.mock.MockMatchEngine;
import com.sixbean.testpike.common.mock.MockScenarioRules;
import com.sixbean.testpike.mockserver.domain.component.LlmFallbackClient;
import com.sixbean.testpike.mockserver.domain.component.MockHitAmqpSink;
import com.sixbean.testpike.mockserver.domain.component.MockHitBatchSink;
import com.sixbean.testpike.mockserver.domain.component.MockHitRecorder;
import com.sixbean.testpike.mockserver.domain.component.MockRecordingFrameAmqpSink;
import com.sixbean.testpike.mockserver.domain.component.MockRecordingFrameBatchSink;
import com.sixbean.testpike.mockserver.domain.component.MockRecordingFrameRecorder;
import com.sixbean.testpike.mockserver.domain.component.MockScenarioStateStore;
import com.sixbean.testpike.mockserver.domain.component.ProxyForwarder;
import com.sixbean.testpike.mockserver.domain.component.RedisMockScenarioStateStore;
import com.sixbean.testpike.mockserver.domain.component.RuleLoader;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.web.client.RestClient;

/**
 * Mock-server entry point: API simulation traffic plane.
 * <p>Management endpoints stay on {@code server.port} (default 18085); an extra
 * Tomcat connector serves mocked traffic on {@code testpike.mock.traffic-port}
 * (default 18086). Rule state is pulled from core — this process never writes
 * business tables and never calls an LLM in-process (keys stay in ai-service).
 * Hit ledger rows travel to core over MQ only (batched, masked before
 * buffering — see {@link MockHitRecorder}).
 * <p>The shared matching kernel lives in testpike-common which ships without a
 * component scan, so it is imported explicitly (IdempotentGuard pattern).
 */
@SpringBootApplication
@EnableScheduling
@Import({MockMatchEngine.class, MockScenarioRules.class, ShutdownLoggingPrewarm.class})
public class MockserverApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockserverApplication.class, args);
    }

    /** Client used by the rule loader to poll the core internal snapshot endpoint. */
    @Bean
    public RestClient coreMockClient(
            @Value("${testpike.mock.load.core-base-url:http://localhost:8083}") String coreBaseUrl) {
        return RestClient.builder().baseUrl(coreBaseUrl).build();
    }

    /**
 * A-3 fallback rung of the miss ladder : rides the SAME
 * core client as the snapshot poll — one outbound channel to the control
 * plane, and the rung's failure posture (degrade to the original miss)
 * lives inside the client, never on the traffic thread.
 */
    @Bean
    public LlmFallbackClient llmFallbackClient(RestClient coreMockClient) {
        return new LlmFallbackClient(coreMockClient);
    }

    /** JSON wire format (Boot's default Java serialization is unreadable cross-language). */
    @Bean
    public MessageConverter mockHitMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /** Overrides the auto-configured template so published batches are plain JSON. */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter mockHitMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(mockHitMessageConverter);
        return template;
    }

    /** Hit ledger transport onto the shared event exchange. */
    @Bean
    public MockHitBatchSink mockHitBatchSink(RabbitTemplate rabbitTemplate) {
        return new MockHitAmqpSink(rabbitTemplate);
    }

    /** Staging buffer of the ledger: thresholds from config, pin identity from the loader. */
    @Bean
    public MockHitRecorder mockHitRecorder(
            MockHitBatchSink sink, RuleLoader ruleLoader,
            @Value("${testpike.mock.hit.batch-size:200}") int batchSize,
            @Value("${testpike.mock.hit.buffer-capacity:5000}") int capacity) {
        return new MockHitRecorder(sink, ruleLoader::pinnedSpaceKey,
                ruleLoader::pinnedVersion, batchSize, capacity);
    }

    /** Recording frame transport onto the shared event exchange . */
    @Bean
    public MockRecordingFrameBatchSink mockRecordingFrameBatchSink(RabbitTemplate rabbitTemplate) {
        return new MockRecordingFrameAmqpSink(rabbitTemplate);
    }

    /** Frame staging buffer: same honesty rules and thresholds as the hit ledger. */
    @Bean
    public MockRecordingFrameRecorder mockRecordingFrameRecorder(
            MockRecordingFrameBatchSink sink,
            @Value("${testpike.mock.recording.batch-size:200}") int batchSize,
            @Value("${testpike.mock.recording.buffer-capacity:5000}") int capacity) {
        return new MockRecordingFrameRecorder(sink, batchSize, capacity);
    }

    /**
     * Forwarding base of the proxy plane: timeouts are configuration keys, the
     * bound target arrives per-request from the pinned snapshot (the allowlist
     * gate was single-point on core at box start — never re-judged here).
     */
    @Bean
    public ProxyForwarder proxyForwarder(
            @Value("${testpike.mock.proxy.connect-timeout-seconds:3}") long connectTimeoutSeconds,
            @Value("${testpike.mock.proxy.read-timeout-seconds:15}") long readTimeoutSeconds) {
        return new ProxyForwarder(java.time.Duration.ofSeconds(connectTimeoutSeconds),
                java.time.Duration.ofSeconds(readTimeoutSeconds));
    }

    /**
 * Scenario lane state lives in Redis and nowhere else :
 * short labels with an idle TTL, never PostgreSQL — mocked traffic at
 * full rate must not put load on the business database.
 */
    @Bean
    public MockScenarioStateStore mockScenarioStateStore(StringRedisTemplate redisTemplate) {
        return new RedisMockScenarioStateStore(redisTemplate);
    }

    /** Extra connector carrying /mocks/** traffic on the dedicated port. */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> mockTrafficConnector(
            @Value("${testpike.mock.traffic-port:18086}") int trafficPort) {
        return factory -> {
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(trafficPort);
            factory.addAdditionalTomcatConnectors(connector);
        };
    }
}
