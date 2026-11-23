package com.sixbean.testpike.api.mcp.infrastructure.remote;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nested-budget contract for the api MCP gateway → core leg (R56 hop floor).
 *
 * <p>Root cause this test pins (L3 evidence, window tp-t635-genl3-slot6):
 * the gateway used the shared 30s internal bridge default, while the
 * generation trigger tool calls the synchronous scenario-preview chain
 * core → ai-service (forward leg default 820s, key
 * "ai.scenario-preview.forward-read-timeout-seconds"). The outer leg died
 * at 30s and surfaced "Request timed out" long before the inner leg could
 * answer — exactly the R56 pattern of one small hop disabling every wider
 * default downstream.
 *
 * <p>Same lineage as admin ScenarioPreviewBridgeWindowTest: each outer hop
 * carries its own key, strictly above the inner worst case, and above the
 * R56 per-hop floor of 800s.
 */
class McpGatewayForwardWindowTest {

    private static final String INNER_KEY = "ai.scenario-preview.forward-read-timeout-seconds";
    private static final String GATEWAY_KEY = "mcp.gateway.forward-read-timeout-seconds";
    private static final int INNER_DEFAULT_SECONDS = 820;

    @Test
    void springWiringConstructorCarriesItsOwnTimeoutKey() {
        Constructor<?> wired = springWiredConstructor();
        Value value = findValueAnnotation(wired, GATEWAY_KEY);
        assertThat(value.value())
                .as("gateway leg must not inherit the shared 30s bridge default")
                .startsWith("${" + GATEWAY_KEY + ":")
                .doesNotContain(INNER_KEY);
    }

    @Test
    void gatewayDefaultIsStrictlyAboveInnerLegAndMeetsThePerHopFloor() {
        int gateway = defaultValueSeconds(springWiredConstructor());
        assertThat(gateway)
                .as("outer leg must outlive the inner core→ai-service leg (820s) with margin")
                .isGreaterThan(INNER_DEFAULT_SECONDS);
        assertThat(gateway)
                .as("R56: every model-request chain hop default >= 800s")
                .isGreaterThanOrEqualTo(800);
    }

    @Test
    void nonSpringConstructorsShareTheSameFloorDefault() {
        long fallback = RestMcpToolGateway.DEFAULT_FORWARD_READ_TIMEOUT_SECONDS;
        assertThat(fallback)
                .as("test/plain constructors must not fall back to the 30s bridge default")
                .isGreaterThan(INNER_DEFAULT_SECONDS)
                .isGreaterThanOrEqualTo(800);
    }

    private static Constructor<?> springWiredConstructor() {
        for (Constructor<?> c : RestMcpToolGateway.class.getDeclaredConstructors()) {
            if (c.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)) {
                return c;
            }
        }
        throw new AssertionError("no @Autowired constructor found on RestMcpToolGateway");
    }

    private static Value findValueAnnotation(Constructor<?> constructor, String keyPrefix) {
        Annotation[][] params = constructor.getParameterAnnotations();
        for (Annotation[] anns : params) {
            for (Annotation a : anns) {
                if (a instanceof Value v && v.value().contains(keyPrefix)) {
                    return v;
                }
            }
        }
        throw new AssertionError("constructor parameter annotated with ${" + keyPrefix
                + "...} not found (gateway hop still rides the shared bridge default?)");
    }

    private static int defaultValueSeconds(Constructor<?> constructor) {
        Value value = findValueAnnotation(constructor, GATEWAY_KEY);
        String spec = value.value();
        int colon = spec.lastIndexOf(':');
        return Integer.parseInt(spec.substring(colon + 1).replace("}", ""));
    }
}
