package com.sixbean.testpike.aiservice.llm.infrastructure.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R56 model-request timeout floor at the production assembly seam.
 *
 * <p>Field evidence (, task 6044): a provider hop carrying a budget
 * minted below the floor caps the whole chain — the parked read only ever
 * wakes when the armed cancel fires, so every legal-but-small K8 hot value
 * (the resolver range reaches down to 10s) silently converts a long model
 * call into a stall-and-retry loop. The floor therefore guards the minting
 * point: whatever the caller asks for, the exchange budget handed to a real
 * provider client is at least 800s; values already at or above the floor
 * pass through untouched ("over-800 manual values stay put", R56).
 *
 * <p>Traceability: rulings.yaml R56; batch anchor and the task-6044 thread
 * dump reference live in docs (red line 16: task numbers stay off code).
 */
class OpenAiCompatClientExchangeFloorTest {

    @Test
    void budgetBelowFloorIsRaisedToTheFloor() {
        assertEquals(OpenAiCompatClient.MODEL_REQUEST_TIMEOUT_FLOOR,
                OpenAiCompatClient.floorModelRequestBudget(Duration.ofSeconds(10)));
        // Duration.ZERO used to mean "never time out" on the JDK seam — the
        // most dangerous shape, it must land on the floor, not on infinity.
        assertEquals(OpenAiCompatClient.MODEL_REQUEST_TIMEOUT_FLOOR,
                OpenAiCompatClient.floorModelRequestBudget(Duration.ZERO));
        assertEquals(OpenAiCompatClient.MODEL_REQUEST_TIMEOUT_FLOOR,
                OpenAiCompatClient.floorModelRequestBudget(Duration.ofSeconds(799)));
    }

    @Test
    void budgetAtOrAboveFloorPassesThroughUntouched() {
        assertEquals(Duration.ofSeconds(800),
                OpenAiCompatClient.floorModelRequestBudget(Duration.ofSeconds(800)));
        assertEquals(Duration.ofSeconds(900),
                OpenAiCompatClient.floorModelRequestBudget(Duration.ofSeconds(900)));
        assertEquals(Duration.ofSeconds(1800),
                OpenAiCompatClient.floorModelRequestBudget(Duration.ofSeconds(1800)));
    }

    @Test
    void nullBudgetNormalizesThroughBoundedFallbackThenFloors() {
        // null caller budget + low install env: the bounded fallback used to
        // hand "300s" straight through — under the floor it must rise to 800.
        assertEquals(OpenAiCompatClient.MODEL_REQUEST_TIMEOUT_FLOOR,
                OpenAiCompatClient.flooredExchangeBudget(null, "300"));
        // env above the floor is honored as-is; garbage/blank keeps the
        // factory default, which equals the floor by contract.
        assertEquals(Duration.ofSeconds(1800),
                OpenAiCompatClient.flooredExchangeBudget(null, "1800"));
        assertEquals(OpenAiCompatClient.MODEL_REQUEST_TIMEOUT_FLOOR,
                OpenAiCompatClient.flooredExchangeBudget(null, null));
        assertEquals(OpenAiCompatClient.MODEL_REQUEST_TIMEOUT_FLOOR,
                OpenAiCompatClient.flooredExchangeBudget(null, "garbage"));
        // a caller budget below the floor is raised even when env is fine —
        // the minting seam, not the install value, owns the last word.
        assertEquals(OpenAiCompatClient.MODEL_REQUEST_TIMEOUT_FLOOR,
                OpenAiCompatClient.flooredExchangeBudget(Duration.ofMinutes(1), "1200"));
    }
}
