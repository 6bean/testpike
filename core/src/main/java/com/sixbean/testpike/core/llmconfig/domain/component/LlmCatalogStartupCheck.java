package com.sixbean.testpike.core.llmconfig.domain.component;

import com.sixbean.testpike.core.llmconfig.api.dto.LlmCatalogConsistencyReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Startup wiring of the catalog consistency probe : once the
 * application is ready, run the probe and fold any finding into warning
 * lines that name where to look (menu path + column), never values —
 * the closed-state guidance posture of the default-on ruling.
 *
 * <p>Read-only verdicts with failure folding: any probe-side exception
 * (database unreachable etc.) becomes one warning line and never blocks
 * startup — the self-check is an observation surface, not a precondition
 * gate for booting.
 */
@Component
public class LlmCatalogStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(LlmCatalogStartupCheck.class);

    /** Guidance target = the llm-configs menu leaf under system settings (routes.ts). */
    private static final String GUIDE = "请到「系统设置 → 大模型配置」页核对该行";

    private final LlmCatalogConsistencyProbe probe;

    public LlmCatalogStartupCheck(LlmCatalogConsistencyProbe probe) {
        this.probe = probe;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        LlmCatalogConsistencyReport report;
        try {
            report = probe.checkFromEnvironment();
        } catch (RuntimeException probeFailure) {
            log.warn("模型清单自检未能完成（{}），本次启动跳过该检查",
                    probeFailure.getClass().getSimpleName());
            return;
        }
        if (!report.hasIssues()) {
            return;
        }
        if (report.catalogEmpty()) {
            log.warn("大模型清单为空：没有任何启用中的生成模型，AI 功能将不可用。{}", GUIDE);
            return;
        }
        if (!report.uncalibratedModels().isEmpty()) {
            log.warn("以下生成模型的提示词上限（prompt_limit_tokens）仍是默认统一值，"
                    + "提取预算将按保守回退窗运行：{}。{}，填该模型供应商公布的输入窗。",
                    report.uncalibratedModels(), GUIDE);
        }
        if (!report.envMismatches().isEmpty()) {
            log.warn("默认生成模型与装机环境变量不一致（仅列名，不含值）：{}。{}，"
                    + "使页面所选与 LLM_MODEL/LLM_BASE_URL/LLM_API_KEY 一致。",
                    report.envMismatches(), GUIDE);
        }
    }
}
