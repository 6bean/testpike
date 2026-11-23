package com.sixbean.testpike.common.util;

import java.util.List;
import java.util.Set;

/**
 * vocabulary base: the verb baseline holds thirty-one entries (16 phase-one + 10
 * mobile-scenario extensions + 3 package-profile + 1 perf assertion + 1 launch-timing
 * recorder appended), keyed
 * identical to the engine descriptor capabilities bitmap
 * ({@code AppautoCatalogSchemaTest.IR_VERBS} reconciliation anchor).
 */
public final class UiActionContract {

    // Contract-wording alignment (docs/04 honest-marking rule): app.* steps
    // without probe candidates / ledger hits must mark locator and expected
    // owned by FidelityGate.controlRefs (single source), this vocabulary base
    // keeps verbs and capability flags only.

 
    public static final String APP_NAMESPACE = "app.";

    /** IR verb baseline, thirty-one frozen-order entries (keyed identical to
     * the engine capabilities bitmap; mirrored single-source in actions_common.py). */
    public static final List<String> IR_VERBS = List.of(
            "launch", "close", "tap", "longPress", "doubleTap", "input", "clearInput",
            "swipe", "scroll", "back", "screenshot", "assertVisible", "assertNotVisible",
            "assertText", "waitVisible", "extractText",
            "deepLink", "openUrl", "rotation", "home", "background", "foreground",
            "clearData", "grantPermission", "wakeUnlock", "networkProfile",
            "install", "uninstall", "verifyVersion", "perf", "launchPerf");

    
    private static final Set<String> IR_VERB_SET = Set.copyOf(IR_VERBS);

 
    public static final Set<String> CAPABILITY_FLAGS = Set.of(
            "webview-hybrid", "network-throttle", "record-native", "flutter", "image-matching");

    /**
 * Assembly-side filter semantics: <b>IR_VERBS value range never shrinks</b>
 * (30 verbs, one mirrored source; deleting a verb breaks stored script contracts);
 */
    public static final Set<String> EXPLORE_FORBIDDEN_VERBS = Set.of("uninstall", "clearData", "install");

    
    public static String exploreForbiddenViolation(String verb) {
        return verb != null && EXPLORE_FORBIDDEN_VERBS.contains(verb)
                ? "探索模式动作黑名单命中 app." + verb + "（卸载/清库/安装类不可逆动词，"
                + "自主探索一律禁生成——黑名单 " + String.join(" / ", EXPLORE_FORBIDDEN_VERBS.stream().sorted().toList())
                + "；该限制仅探索模式，用例正写链路不受影响）"
                : null;
    }

 
    public static final String WEBVIEW_STEP_FLAG = "webview";

    
    public static boolean stepRequestsWebview(java.util.Map<String, ?> step) {
        return step != null && Boolean.TRUE.equals(step.get(WEBVIEW_STEP_FLAG));
    }

    private UiActionContract() {
    }

    
    public static boolean isAppAction(String action) {
        return action != null && action.startsWith(APP_NAMESPACE);
    }

    
    public static boolean isBaselineVerb(String action) {
        return isAppAction(action) && IR_VERB_SET.contains(action.substring(APP_NAMESPACE.length()));
    }

    
    public static List<String> prunedVerbs(java.util.Map<String, Boolean> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return List.of();
        }
        return IR_VERBS.stream()
                .filter(v -> Boolean.TRUE.equals(capabilities.get(v)))
                .toList();
    }

    
    public static String appActionViolation(String action) {
        if (!isAppAction(action)) {
            return null;
        }
        String verb = action.substring(APP_NAMESPACE.length());
        if (IR_VERB_SET.contains(verb)) {
            return null;
        }
        return "app." + verb + " 不在抽象动作 IR 基线——引擎无关动作仅基线 "
                + IR_VERBS.size() + " 枚动词："
                + String.join(" / ", IR_VERBS)
                + "（换引擎零改用例是 IR 的命门；平台专属能力请走引擎描述符能力位而非新增动词）";
    }
}
