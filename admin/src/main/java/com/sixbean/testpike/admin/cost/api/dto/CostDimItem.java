package com.sixbean.testpike.admin.cost.api.dto;

import java.math.BigDecimal;


public record CostDimItem(String value, BigDecimal amount, long calls) {
}
