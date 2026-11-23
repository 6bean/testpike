package com.sixbean.testpike.common.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract for the kernel's single denial-guidance frame source (invariant 4
 * of the behaviour-kernel hardening rulings): every "feature is switched
 * off" refusal produced by the reasoning pipeline must carry both elements —
 * the literal settings path and the literal config key name(s) — so a user
 * told "no" also learns exactly where to turn the capability back on.
 */
class DenialGuidanceFrameTest {

    @Test
    void singleKeyFrameCarriesPathAndKeyNameLiterals() {
        String frame = DenialGuidanceFrame.switchClosed("矩阵智能抽样", "ai.matrix-sample.enabled");
        assertTrue(frame.contains("未开启"), frame);
        assertTrue(frame.contains("系统管理"), "frame must name the settings path: " + frame);
        assertTrue(frame.contains("系统配置"), "frame must name the settings page: " + frame);
        assertTrue(frame.contains("ai.matrix-sample.enabled"),
                "frame must name the config key literally: " + frame);
    }

    @Test
    void multiKeyFrameListsEveryKeySoDualGatesAreHonest() {
        String frame = DenialGuidanceFrame.switchClosed("视觉定位兜底",
                "ai.vision.enabled", "ai.vision.locate.enabled");
        assertTrue(frame.contains("ai.vision.enabled"), frame);
        assertTrue(frame.contains("ai.vision.locate.enabled"),
                "a dual-gate refusal must point at both keys: " + frame);
    }

    @Test
    void frameStatesTheZeroCallConsequenceForTheReader() {
        String frame = DenialGuidanceFrame.switchClosed("录制转流程", "ai.record-to-flow.enabled");
        assertTrue(frame.contains("零模型调用"),
                "refusal must state what did not happen: " + frame);
    }

    @Test
    void afterAttemptFrameNamesPathKeysAndTheHonestConsequence() {
        String frame = DenialGuidanceFrame.switchClosedAfterAttempt(
                "对话上下文自愈", "本次回答的其他部分不受影响", "ai.ctx.over-budget-heal.enabled");
        assertTrue(frame.contains("系统管理 → 系统配置"), frame);
        assertTrue(frame.contains("ai.ctx.over-budget-heal.enabled"), frame);
        assertTrue(frame.contains("本次回答的其他部分不受影响"),
                "the caller-provided consequence must survive: " + frame);
        assertTrue(!frame.contains("零模型调用"),
                "an after-attempt refusal must not claim zero calls: " + frame);
    }
}
