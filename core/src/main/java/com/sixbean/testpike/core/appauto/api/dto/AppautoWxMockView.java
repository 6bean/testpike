package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;
import java.util.Map;


public record AppautoWxMockView(
        List<Map<String, Object>> mocks,
        int pruned,
        List<String> pruneReasons) {

    
    public AppautoWxMockView(List<Map<String, Object>> mocks, int pruned) {
        this(mocks, pruned, List.of());
    }
}
