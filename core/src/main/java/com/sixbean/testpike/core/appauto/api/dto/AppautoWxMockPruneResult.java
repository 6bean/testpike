package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;
import java.util.Map;


public record AppautoWxMockPruneResult(
        List<Map<String, Object>> kept,
        int pruned,
        List<String> reasons) {
}
