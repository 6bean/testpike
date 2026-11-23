package com.sixbean.testpike.common.report;

import java.math.BigDecimal;
import java.math.RoundingMode;


public final class BadgeRatePhrase {

 
    public static final String NO_TERMINAL = "暂无终态任务";

    private BadgeRatePhrase() {
    }

    
    public static String of(BigDecimal passRate) {
        if (passRate == null) {
            return NO_TERMINAL;
        }
        return "通过率 " + passRate.multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
