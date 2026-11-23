package com.sixbean.testpike.core.executor.api.dto;

import com.sixbean.testpike.core.cluster.api.dto.ClusterComponentInfo;

import java.util.List;
import java.util.Map;


public record ExecutorSelfCheckView(long executorId, String name, String version,
                                    int status, String statusText, String lastHeartbeat,
                                    boolean capabilitiesReported, String hostOs,
                                    Map<String, Object> tools, List<String> missingTools,
                                    Map<String, Object> runtimes, List<String> engines,
                                    List<String> platforms, List<String> deviceKinds,
                                    Long deviceCount, List<ClusterComponentInfo> components) {
}
