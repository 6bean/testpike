package com.sixbean.testpike.admin.ai.infrastructure.remote.client;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nested-budget contract for the admin → core scenario-preview leg (R43
 * drill-down of the paired HttpTimeoutException red signatures): the outer
 * bridge leg used to read the same configuration key and default (820s) as
 * the inner core → ai-service leg, so when the inner leg expired the outer
 * leg fired at the same instant and both legs surfaced as one permanent
 * pair. The outer leg must therefore carry its own key, strictly above the
 * inner worst case, and above the R56 per-hop floor of 800s.
 *
 * Inner leg source of truth (core module, not on this test classpath):
 * RestAiServiceScenarioPreviewClient reads
 * "ai.scenario-preview.forward-read-timeout-seconds" with default 820.
 */
class ScenarioPreviewBridgeWindowTest {

    private static final String INNER_KEY = "ai.scenario-preview.forward-read-timeout-seconds";
    private static final String OUTER_KEY = "ai.scenario-preview.bridge-read-timeout-seconds";
    private static final int INNER_DEFAULT_SECONDS = 820;

    @Test
    void outerLegReadsItsOwnKeyNotTheInnerForwardKey() {
        Value value = timeoutValueAnnotation();
        assertThat(value.value())
                .as("admin bridge leg must not share the inner core→ai-service key")
                .startsWith("${" + OUTER_KEY + ":")
                .doesNotContain(INNER_KEY);
    }

    @Test
    void outerDefaultIsStrictlyAboveInnerLegAndMeetsThePerHopFloor() {
        int outer = defaultValueSeconds();
        assertThat(outer)
                .as("outer leg must outlive the inner leg (820s) with margin, "
                        + "otherwise both legs throw HttpTimeoutException in pairs")
                .isGreaterThan(INNER_DEFAULT_SECONDS);
        assertThat(outer)
                .as("R56: every model-request chain hop default >= 800s")
                .isGreaterThanOrEqualTo(800);
    }

    private static Value timeoutValueAnnotation() {
        Constructor<?>[] constructors = RestCoreAiScenarioPreviewClient.class.getDeclaredConstructors();
        assertThat(constructors)
                .as("single constructor expected")
                .hasSize(1);
        for (Annotation annotation : constructors[0].getParameterAnnotations()[1]) {
            if (annotation instanceof Value value) {
                return value;
            }
        }
        throw new AssertionError("no @Value on the timeout constructor parameter");
    }

    private static int defaultValueSeconds() {
        String expression = timeoutValueAnnotation().value();
        int colon = expression.lastIndexOf(':');
        return Integer.parseInt(expression.substring(colon + 1, expression.length() - 1));
    }
}
