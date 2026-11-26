package com.sixbean.testpike.core.perf.domain.component;

import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Single gate for the two AI load-test drafting points (B-11): the scenario
 * draft that prefills the wizard and the run-insight read-out on a finished
 * report. One K8 key per point — 7a discipline — and both default closed, so
 * an operator must open a point explicitly before any token is spent.
 * <p>Only an explicit "true" row opens a point; a missing, blank or mistyped
 * row keeps it closed (a dirty config row can never switch an AI point on by
 * accident). A refusal names the exact key to flip, so the UI can show the
 * operator where to act instead of a generic "feature disabled".
 */
@Component
public class PerfAiSwitchGate {

    /** AI drafting point: turn a sentence into a wizard-prefilled scenario draft. */
    public static final String POINT_SCENARIO_DRAFT = "scenario-draft";
    /** AI read-out point: plain-word explanation of a finished run's attribution. */
    public static final String POINT_INSIGHT = "insight";

    /** Point name -> its K8 switch key (one point one switch, both default off). */
    private static final Map<String, String> SWITCH_KEYS = Map.of(
            POINT_SCENARIO_DRAFT, "ai.perf.scenario-draft",
            POINT_INSIGHT, "ai.perf.insight");

    private final ConfigService configService;

    public PerfAiSwitchGate(ConfigService configService) {
        this.configService = configService;
    }

    /** Whether an AI point is currently open (read live per call = hot-updatable). */
    public boolean isOpen(String point) {
        String key = SWITCH_KEYS.get(point);
        return key != null && "true".equalsIgnoreCase(
                configService.getValue(key, "false").trim());
    }

    /**
     * Refuse with FORBIDDEN unless the point is open, naming the exact switch
     * key; an unknown point name refuses too — a caller bug must never be read
     * as "feature closed" or, worse, silently pass.
     */
    public void requireOpen(String point) {
        String key = SWITCH_KEYS.get(point);
        if (key == null) {
            throw new ServiceException(
                    "未知的压测 AI 应用点「" + point + "」，本期可选值："
                            + String.join(" / ", SWITCH_KEYS.keySet())
                            + "——请在 AI 对话或压测工作台使用上述应用点名；开关在「系统配置」按 ai.perf.scenario-draft / ai.perf.insight 键启用（默认关）",
                    BizErrorCode.FORBIDDEN);
        }
        if (!"true".equalsIgnoreCase(configService.getValue(key, "false").trim())) {
            throw new ServiceException(DenialGuidanceFrame.switchClosed(
                    "压测 AI 应用点「" + point + "」", key), BizErrorCode.FORBIDDEN);
        }
    }
}
