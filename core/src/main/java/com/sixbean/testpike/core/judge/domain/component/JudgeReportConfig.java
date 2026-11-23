package com.sixbean.testpike.core.judge.domain.component;

import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.springframework.stereotype.Component;


@Component
public class JudgeReportConfig {

    static final double DEFAULT_DIFF_ALPHA = 0.05;

    private final ConfigService configService;

    public JudgeReportConfig(ConfigService configService) {
        this.configService = configService;
    }

    
    public Double pricePer1kTokens() {
        Double v = JudgeConfigValues.parseDouble(
                configService.getValue("judge.cost.price-per-1k-tokens", ""),
                0.0, Double.MAX_VALUE);
        return v == null || v <= 0.0 ? null : v;
    }

    
    public double diffAlpha() {
        Double v = JudgeConfigValues.parseDouble(
                configService.getValue("judge.report.diff-alpha", ""), 0.0, 1.0);
        return v == null || v <= 0.0 || v >= 1.0 ? DEFAULT_DIFF_ALPHA : v;
    }
}
