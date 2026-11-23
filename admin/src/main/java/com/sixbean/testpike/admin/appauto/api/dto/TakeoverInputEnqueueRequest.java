package com.sixbean.testpike.admin.appauto.api.dto;

import java.util.List;
import java.util.Map;


public record TakeoverInputEnqueueRequest(List<Map<String, Object>> commands) {
}
