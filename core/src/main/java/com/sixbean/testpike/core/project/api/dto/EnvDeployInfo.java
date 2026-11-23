package com.sixbean.testpike.core.project.api.dto;


public record EnvDeployInfo(String deployedVersion, String deployedAt, Long deployedBy,
                            String deployedByName, java.util.List<EnvDeployBatchRef> batches) {
}
