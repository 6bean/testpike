package com.sixbean.testpike.admin.plans.api.dto;


public record ExitCriteriaRequest(Double minPassRate, Boolean noOpenDefects,
                                  String evalRubricName, String evalRubricDomain,
                                  Double evalMinPassRate) {
}
