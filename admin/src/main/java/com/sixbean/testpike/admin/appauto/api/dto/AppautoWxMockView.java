package com.sixbean.testpike.admin.appauto.api.dto;

import java.util.List;
import java.util.Map;


public record AppautoWxMockView(
        List<Map<String, Object>> mocks,
        int pruned,
        List<String> pruneReasons) {
}
