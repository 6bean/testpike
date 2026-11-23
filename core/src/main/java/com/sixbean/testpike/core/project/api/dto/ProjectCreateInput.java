package com.sixbean.testpike.core.project.api.dto;

import java.math.BigDecimal;


public record ProjectCreateInput(String name, String code, String defaultLang,
                                 String defaultPriority, BigDecimal budgetMonthly,
                                 Long templateId) {

    
    public ProjectCreateInput(String name, String code, String defaultLang,
                              String defaultPriority, BigDecimal budgetMonthly) {
        this(name, code, defaultLang, defaultPriority, budgetMonthly, null);
    }
}
