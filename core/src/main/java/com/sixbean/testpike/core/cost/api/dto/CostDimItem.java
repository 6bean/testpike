package com.sixbean.testpike.core.cost.api.dto;

import java.math.BigDecimal;


public record CostDimItem(String value, BigDecimal amount, long calls) {
}
