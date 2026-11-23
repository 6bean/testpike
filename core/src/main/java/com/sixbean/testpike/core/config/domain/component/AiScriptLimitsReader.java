package com.sixbean.testpike.core.config.domain.component;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;


@Component
public class AiScriptLimitsReader {

    
    public static final String KEY_MAX_STEPS = "ai.script.max-steps";
    public static final String KEY_MAX_LOOP = "ai.script.max-loop";
    public static final String KEY_MAX_NEST_DEPTH = "ai.script.max-nest-depth";
    
    public static final String KEY_ASSERT_TIMEOUT_SEC = "ai.script.assert-timeout-sec";
    public static final String KEY_SCREENSHOT_MODE = "ai.script.screenshot-mode";
    public static final String KEY_FAIL_POLICY = "ai.script.fail-policy";

 
    public static final int DEFAULT_MAX_STEPS = 50;
    public static final int DEFAULT_MAX_LOOP = 20;
    public static final int DEFAULT_MAX_NEST_DEPTH = 5;
    public static final int DEFAULT_ASSERT_TIMEOUT_SEC = 10;
    public static final int DEFAULT_SCREENSHOT_MODE = 3;
    public static final int DEFAULT_FAIL_POLICY = 1;

    private final SystemConfigReader configReader;

    public AiScriptLimitsReader(SystemConfigReader configReader) {
        this.configReader = configReader;
    }

    public int maxSteps() {
        return configReader.getInt(KEY_MAX_STEPS, DEFAULT_MAX_STEPS);
    }

    public int maxLoop() {
        return configReader.getInt(KEY_MAX_LOOP, DEFAULT_MAX_LOOP);
    }

    public int maxNestDepth() {
        return configReader.getInt(KEY_MAX_NEST_DEPTH, DEFAULT_MAX_NEST_DEPTH);
    }

    
    public Map<String, Integer> toClaimPayload() {
        Map<String, Integer> limits = new LinkedHashMap<>();
        limits.put("max-steps", maxSteps());
        limits.put("max-loop", maxLoop());
        limits.put("max-nest-depth", maxNestDepth());
        limits.put("assert-timeout-sec", configReader.getInt(KEY_ASSERT_TIMEOUT_SEC, DEFAULT_ASSERT_TIMEOUT_SEC));
        limits.put("screenshot-mode", configReader.getInt(KEY_SCREENSHOT_MODE, DEFAULT_SCREENSHOT_MODE));
        limits.put("fail-policy", configReader.getInt(KEY_FAIL_POLICY, DEFAULT_FAIL_POLICY));
        return limits;
    }
}
