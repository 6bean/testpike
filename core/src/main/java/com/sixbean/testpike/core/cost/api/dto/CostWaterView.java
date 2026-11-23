package com.sixbean.testpike.core.cost.api.dto;

import java.math.BigDecimal;


public record CostWaterView(String month, long costCents, long budgetCents, int alertPct,
                            boolean breakEnabled, boolean overrideActive) {

    
    public static String yuan(long cents) {
        return "¥" + BigDecimal.valueOf(cents).movePointLeft(2)
                .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
