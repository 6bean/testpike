package com.sixbean.testpike.core.executor.api.dto;

import java.util.Map;


public record ExecutorRegisterInput(String name, String groupIds, String envIds,
                                    String endTypes, String browsers, int capacity,
                                    String version, int runningTasks,
                                    Map<String, Object> capabilities) {

 
    public ExecutorRegisterInput(String name, String groupIds, String envIds,
                                 String endTypes, String browsers, int capacity,
                                 String version, int runningTasks) {
        this(name, groupIds, envIds, endTypes, browsers, capacity, version, runningTasks, null);
    }
}
