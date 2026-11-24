package com.sixbean.testpike.core.mock.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 contract for the R63 three-element refusal wording of the mock runtime
 * doors (recording box, hit promotion, hit-ledger landing, LLM fallback
 * store). Every sentence must echo the offending value, quote an on-screen
 * repair entry in 「」, and carry an action word — the same shape the
 * error-guidance gate checks mechanically, pinned here without booting
 * infrastructure.
 */
class MockRuntimeRefusalsL2Test {

    private static void assertThreeElements(String text, String... echoedValues) {
        for (String value : echoedValues) {
            assertTrue(text.contains(value), "offending value not echoed: " + value + " in [" + text + "]");
        }
        assertTrue(text.contains("请"), "missing action word 请: " + text);
        assertTrue(text.matches(".*「[^」]+」.*"), "missing literal repair entry in 「」: " + text);
    }

    @Test
    void refusesRecordingOnSpaceThatIsNotPublished() {
        assertThreeElements(MockRuntimeRefusals.recordingSpaceNotPublished(42L), "42");
    }

    @Test
    void refusesBlankRecordingTarget() {
        assertThreeElements(MockRuntimeRefusals.recordingTargetBlank());
    }

    @Test
    void echoesIllegalRecordingTarget() {
        assertThreeElements(MockRuntimeRefusals.recordingTargetIllegal("svc.local"), "svc.local");
    }

    @Test
    void refusesSecondOpenBoxOnSameSpace() {
        assertThreeElements(MockRuntimeRefusals.recordingBoxAlreadyOpen(7L), "7");
    }

    @Test
    void refusesSealingASealedBox() {
        assertThreeElements(MockRuntimeRefusals.recordingBoxAlreadySealed(9L, "stopped"), "9", "stopped");
    }

    @Test
    void refusesGhostRecordingBox() {
        assertThreeElements(MockRuntimeRefusals.recordingBoxMissing(11L), "11");
    }

    @Test
    void refusesPromotionAcrossSpaces() {
        assertThreeElements(MockRuntimeRefusals.hitSpaceMismatch(1L, 2L, 3L), "1", "2", "3");
    }

    @Test
    void refusesPromotionWithoutRecordedResponse() {
        assertThreeElements(MockRuntimeRefusals.hitNoResponseToPromote(5L), "5");
    }

    @Test
    void refusesPromotionOfCleanedHit() {
        assertThreeElements(MockRuntimeRefusals.hitRowMissing(6L), "6");
    }

    @Test
    void refusesHitLedgerWithoutSpaceKey() {
        assertThreeElements(MockRuntimeRefusals.hitLedgerSpaceKeyBlank());
    }

    @Test
    void refusesHitLedgerWithUnknownSpaceKey() {
        assertThreeElements(MockRuntimeRefusals.hitLedgerSpaceKeyUnknown("ghost-api"), "ghost-api");
    }

    @Test
    void refusesFallbackStoreWithoutSpaceId() {
        assertThreeElements(MockRuntimeRefusals.fallbackSpaceIdMissing());
    }

    @Test
    void refusesFallbackStoreWithIllegalStatus() {
        assertThreeElements(MockRuntimeRefusals.fallbackStatusOutOfRange(999), "999");
    }

    @Test
    void refusesFallbackStoreWithNullBody() {
        assertThreeElements(MockRuntimeRefusals.fallbackBodyNull());
    }

    @Test
    void refusesFallbackStoreWhenSnapshotUnserializable() {
        assertThreeElements(MockRuntimeRefusals.fallbackSnapshotUnserializable("循环引用"), "循环引用");
    }

    @Test
    void namesBothFallbackSwitchKeysWhenGateClosed() {
        String master = MockRuntimeRefusals.fallbackGateClosed("Mock 模块 AI 总闸", "ai.mock.enabled");
        String sub = MockRuntimeRefusals.fallbackGateClosed("应用点「llm-fallback」分开关", "ai.mock.llm-fallback");
        assertThreeElements(master, "ai.mock.enabled");
        assertThreeElements(sub, "ai.mock.llm-fallback");
        assertTrue(master.contains("Mock 模块 AI 总闸"));
        assertTrue(sub.contains("llm-fallback"));
    }
}
