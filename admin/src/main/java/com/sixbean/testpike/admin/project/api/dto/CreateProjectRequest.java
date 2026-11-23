package com.sixbean.testpike.admin.project.api.dto;

import java.math.BigDecimal;


public record CreateProjectRequest(String name, String code, String defaultLang,
                                   String defaultPriority, BigDecimal budgetMonthly,
                                   Long templateId) {

    
    public CreateProjectRequest(String name, String code, String defaultLang,
                                String defaultPriority, BigDecimal budgetMonthly) {
        this(name, code, defaultLang, defaultPriority, budgetMonthly, null);
    }
}
