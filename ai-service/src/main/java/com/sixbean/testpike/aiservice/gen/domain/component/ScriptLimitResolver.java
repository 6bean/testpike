package com.sixbean.testpike.aiservice.gen.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans;


public class ScriptLimitResolver {

 
    public static final String KEY_MAX_STEPS = "ai.script.max-steps";
    public static final String KEY_MAX_NEST_DEPTH = "ai.script.max-nest-depth";
    public static final String KEY_MAX_LOOP = "ai.script.max-loop";
    public static final String KEY_ASSERT_TIMEOUT_SEC = "ai.script.assert-timeout-sec";
    public static final String KEY_DEFAULT_WAIT_SEC = "ai.script.default-wait-sec";
    public static final String KEY_SMART_WAIT = "ai.script.smart-wait";
    public static final String KEY_AUTO_SCROLL = "ai.script.auto-scroll";
    public static final String KEY_SCREENSHOT_MODE = "ai.script.screenshot-mode";
    public static final String KEY_FAIL_POLICY = "ai.script.fail-policy";
    // Scheduling knobs of step-group slicing (V489). They are NOT prompt text:
    // the constraint segment above keeps its legacy bytes so an unrelated config
    // change never shifts the frozen system prefix (AP-6 / prompt-cache economy).
    public static final String KEY_SLICE_STEPS = "ai.script.slice-steps";
    public static final String KEY_SLICE_TIMEOUT_SECONDS = "ai.script.slice-timeout-seconds";
    public static final String KEY_SLICE_RETRY = "ai.script.slice-retry";

 
    static final int DEFAULT_MAX_STEPS = 50;
    static final int DEFAULT_MAX_NEST_DEPTH = 5;
    static final int DEFAULT_MAX_LOOP = 20;
    static final int DEFAULT_ASSERT_TIMEOUT_SEC = 10;
    static final int DEFAULT_DEFAULT_WAIT_SEC = 3;
    static final boolean DEFAULT_SMART_WAIT = true;
    static final boolean DEFAULT_AUTO_SCROLL = true;
    static final int DEFAULT_SCREENSHOT_MODE = 3;
    static final int DEFAULT_FAIL_POLICY = 1;
    static final int DEFAULT_SLICE_STEPS = 10;
    static final int DEFAULT_SLICE_TIMEOUT_SECONDS = 900;
    static final int DEFAULT_SLICE_RETRY = 1;

    private final CoreGateway coreGateway;

    public ScriptLimitResolver(CoreGateway coreGateway) {
        this.coreGateway = coreGateway;
    }

    
    public String promptConstraintSegment() {
        return "单用例步骤 ≤" + intConfig(KEY_MAX_STEPS, DEFAULT_MAX_STEPS)
                + "、嵌套 ≤" + intConfig(KEY_MAX_NEST_DEPTH, DEFAULT_MAX_NEST_DEPTH)
                + "、loop ≤" + intConfig(KEY_MAX_LOOP, DEFAULT_MAX_LOOP)
                + "；断言超时 " + intConfig(KEY_ASSERT_TIMEOUT_SEC, DEFAULT_ASSERT_TIMEOUT_SEC) + "s"
                + "、默认等待 " + intConfig(KEY_DEFAULT_WAIT_SEC, DEFAULT_DEFAULT_WAIT_SEC) + "s"
                + "；智能等待" + onOff(boolConfig(KEY_SMART_WAIT, DEFAULT_SMART_WAIT))
                + "、自动滚动" + onOff(boolConfig(KEY_AUTO_SCROLL, DEFAULT_AUTO_SCROLL))
                + "；截图策略=" + screenshotMode(intConfig(KEY_SCREENSHOT_MODE, DEFAULT_SCREENSHOT_MODE))
                + "、失败策略=" + failPolicy(intConfig(KEY_FAIL_POLICY, DEFAULT_FAIL_POLICY))
                + "。";
    }

 
    private int intConfig(String key, int fallback) {
        String raw = coreGateway.getSysConfig(key);
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
 * segment width: steps per generated group (0 = slicing off, the whole case
 * goes out as the legacy single pass). Hot read, no cache.
 */
    public int sliceSteps() {
        return rangedInt(KEY_SLICE_STEPS, DEFAULT_SLICE_STEPS, 0, 100);
    }

    /**
     * Per-group model-request read timeout seconds. The lower bound keeps the
     * >=800s per-hop floor for LLM requests; an out-of-range row falls back to
     * the contract default instead of strangling the in-flight task.
     */
    public int sliceTimeoutSeconds() {
        return rangedInt(KEY_SLICE_TIMEOUT_SECONDS, DEFAULT_SLICE_TIMEOUT_SECONDS, 800, 3600);
    }

    /** Retry attempts for a failed group (0 = no retry). */
    public int sliceRetry() {
        return rangedInt(KEY_SLICE_RETRY, DEFAULT_SLICE_RETRY, 0, 3);
    }

    /** Read side defence for the segment keys: missing/dirty/out-of-range → default. */
    private int rangedInt(String key, int fallback, int lower, int upper) {
        String raw = coreGateway.getSysConfig(key);
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= lower && value <= upper ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    
    private boolean boolConfig(String key, boolean fallback) {
        return SysConfigBooleans.parse(coreGateway.getSysConfig(key), fallback);
    }

    private static String onOff(boolean on) {
        return on ? "开启" : "关闭";
    }

 
    private static String screenshotMode(int mode) {
        return switch (mode) {
            case 1 -> "每步";
            case 2 -> "关键步";
            case 3 -> "仅失败";
            default -> "仅失败";
        };
    }

 
    private static String failPolicy(int policy) {
        return policy == 2 ? "立即中止" : "继续后续步骤";
    }
}
