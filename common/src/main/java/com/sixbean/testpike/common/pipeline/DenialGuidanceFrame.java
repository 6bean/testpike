package com.sixbean.testpike.common.pipeline;

/**
 * L0 kernel piece that renders the one honest "feature switched off" refusal
 * frame for the reasoning pipeline (behaviour-kernel invariant 4).
 * <p>Every reasoning domain used to hand-write its own switch-off sentence,
 * which drifted: some named the settings page, some only the key, some
 * neither. The kernel owns this text now — one frame, two mandatory elements
 * (literal settings navigation path + literal config key names) plus the
 * consequence the caller must state (zero model calls, since the refusal is
 * raised before any request goes out). Domains pass feature name and keys;
 * they must not compose their own denial sentences (gate:
 * tools/denial_guidance_gate.py).
 * <p>Pure string assembly — no I/O, no config reads; the caller already holds
 * the switch values when it decides to refuse.
 */
public final class DenialGuidanceFrame {

    /** Literal navigation path to the configuration page, as shown in the UI menu. */
    private static final String SETTINGS_PATH = "「系统管理 → 系统配置」";

    private DenialGuidanceFrame() {
    }

    /**
     * Renders the switched-off refusal: names the feature, points at the
     * settings path, lists every config key involved (dual-gate features must
     * name both the master switch and the per-feature switch, or the guidance
     * is only half honest), and states the zero-model-call consequence.
     *
     * @param featureName human name of the capability being refused
     * @param configKeys  literal config key(s) that gate the capability
     * @return the unified denial frame text for ServiceException
     */
    public static String switchClosed(String featureName, String... configKeys) {
        return switchClosedAfterAttempt(featureName, "零模型调用", configKeys);
    }

    /**
     * Renders the switched-off refusal raised AFTER an attempt already went
     * out (e.g. a provider rejected an oversized packet and the retry ring
     * is switched off). Stating "zero model calls" there would be dishonest,
     * so the caller supplies the truthful consequence while the frame still
     * owns the shared sentence: literal settings path + literal key names.
     */
    public static String switchClosedAfterAttempt(String featureName, String consequence,
                                                  String... configKeys) {
        String keys = String.join(" 与 ", configKeys);
        return featureName + "当前未开启：请在" + SETTINGS_PATH + "搜索 " + keys
                + " 并打开后重试，" + consequence;
    }
}
