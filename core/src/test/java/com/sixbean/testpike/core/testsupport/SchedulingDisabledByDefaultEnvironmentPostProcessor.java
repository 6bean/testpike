package com.sixbean.testpike.core.testsupport;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Test-classpath default for the core scheduler master gate.
 *
 * <p>All {@code @Scheduled} components in core sit behind
 * {@code @ConditionalOnProperty(name = "testpike.scheduling.enabled", matchIfMissing = true)}.
 * matchIfMissing=true keeps the production runtime enabled (zero touch: this class ships
 * nowhere, it lives under src/test only). In L2 contexts however "unset" previously meant
 * "enabled", so scheduler tasks fired mid-test and contended the shared database with
 * long-running transactions (forensics 2026-11-03: single shared scheduling pool plus
 * idle-in-transaction holders during core test windows).
 *
 * <p>This post-processor appends a LAST-precedence property source resolving the gate to
 * "false", so a plain {@code @SpringBootTest} boots with no scheduled tasks. Any explicit
 * source wins over it: {@code @TestPropertySource} opt-ins (tests that genuinely autowire a
 * scheduler bean), {@code -D} overrides and application configuration files are all ahead
 * of an addLast source, keeping per-test semantics unchanged.
 */
public class SchedulingDisabledByDefaultEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String SCHEDULING_MASTER_GATE = "testpike.scheduling.enabled";

    private static final String TEST_DEFAULTS_SOURCE = "coreTestSchedulingDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(
                new MapPropertySource(TEST_DEFAULTS_SOURCE, Map.of(SCHEDULING_MASTER_GATE, "false")));
    }
}
