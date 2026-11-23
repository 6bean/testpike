package com.sixbean.testpike.aiservice.doc.domain.component;

import java.util.List;


public record ReducePlan(int levels, List<Integer> levelGroupCounts, int totalSummaryCalls) {

    
    public int reduceCalls() {
        return totalSummaryCalls - levelGroupCounts.get(0);
    }
}
