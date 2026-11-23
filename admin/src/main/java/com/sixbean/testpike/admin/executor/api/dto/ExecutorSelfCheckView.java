package com.sixbean.testpike.admin.executor.api.dto;

import com.sixbean.testpike.admin.cluster.api.dto.ClusterComponentView;

import java.util.List;
import java.util.Map;


public record ExecutorSelfCheckView(long executorId, String name, String version,
                                    int status, String statusText, String lastHeartbeat,
                                    boolean capabilitiesReported, String hostOs,
                                    Map<String, Object> tools, List<String> missingTools,
                                    Map<String, Object> runtimes, List<String> engines,
                                    List<String> platforms, List<String> deviceKinds,
                                    Long deviceCount, List<ClusterComponentView> components) {
}
