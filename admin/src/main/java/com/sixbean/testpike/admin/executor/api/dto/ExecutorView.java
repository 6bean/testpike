package com.sixbean.testpike.admin.executor.api.dto;

import java.util.Map;


public record ExecutorView(long id, String name, String groupIds, String envIds,
                           String endTypes, String browsers, int capacity, int runningCnt,
                           int status, String version, String lastHeartbeat,
                           Map<String, Object> capabilities) {
}
