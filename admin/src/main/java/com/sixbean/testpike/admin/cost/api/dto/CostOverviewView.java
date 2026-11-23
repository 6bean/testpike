package com.sixbean.testpike.admin.cost.api.dto;

import java.math.BigDecimal;
import java.util.List;


public record CostOverviewView(String month, Long projectId, CostDimItem total,
                               List<CostDimItem> byProject, List<CostDimItem> byModel,
                               List<CostDimItem> byCaller, List<CostDimItem> byDay) {
}
