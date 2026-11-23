package com.sixbean.testpike.admin.judge.infrastructure.remote.client;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Forward-window contract for the question-generation leg :
 * the admin to ai-service qa-gen call is a synchronous per-group LLM pass, and the
 * live spectrum on document 9016 (642 chunks -> 17 groups) proved the old hard-coded
 * 10-minute window is exceeded even by a healthy run, so the window must come from
 * configuration instead of a constant. The shipped default is derived from measured
 * numbers, not guesswork: a healthy single-group leg runs 25-60s (same-model scenario
 * enumeration legs measured 29-37s), 17 groups at the cost-gate ceiling therefore need
 * roughly 1020s, and 1800s keeps a ~1.76x margin. A stalled provider must be bounded
 * by the provider-side timeout on ai-service, never by inflating this forward window.
 */
class RestJudgeAiClientForwardWindowTest {

    private static final String WINDOW_KEY = "ai.judge.qa-gen.forward-read-timeout-seconds";

    @Test
    void forwardWindowComesFromConfigurationNotTheHardDefault() {
        RestJudgeAiClient client = new RestJudgeAiClient("http://localhost:18084", 42);
        assertThat(client.qaGenForwardWindow()).isEqualTo(Duration.ofSeconds(42));
    }

    @Test
    void shippedDefaultMatchesTheMeasuredWorstCaseBudgetAndKeyIsNamed() {
        Constructor<RestJudgeAiClient> constructor = windowedConstructor();
        int windowParameter = -1;
        for (int i = 0; i < constructor.getParameterTypes().length; i++) {
            if (constructor.getParameterTypes()[i] == long.class
                    || constructor.getParameterTypes()[i] == Long.class) {
                windowParameter = i;
            }
        }
        assertThat(windowParameter).as("constructor carries the window seconds parameter")
                .isNotEqualTo(-1);
        Value binding = (Value) findValueAnnotation(constructor, windowParameter);
        assertThat(binding).as("window parameter is property-bound").isNotNull();
        String expression = binding.value();
        assertThat(expression)
                .contains(WINDOW_KEY)
                .contains(":1800");
    }

    private Constructor<RestJudgeAiClient> windowedConstructor() {
        for (Constructor<?> candidate : RestJudgeAiClient.class.getDeclaredConstructors()) {
            @SuppressWarnings("unchecked")
            Constructor<RestJudgeAiClient> typed = (Constructor<RestJudgeAiClient>) candidate;
            if (candidate.getParameterCount() == 2) {
                return typed;
            }
        }
        throw new AssertionError("RestJudgeAiClient must expose a two-argument constructor "
                + "(base url + forward window seconds) for the install-tunable qa-gen leg");
    }

    private Annotation findValueAnnotation(Constructor<RestJudgeAiClient> constructor, int index) {
        for (Annotation annotation : constructor.getParameterAnnotations()[index]) {
            if (annotation instanceof Value) {
                return annotation;
            }
        }
        return null;
    }
}
