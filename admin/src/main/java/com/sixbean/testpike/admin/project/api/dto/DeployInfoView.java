package com.sixbean.testpike.admin.project.api.dto;

import java.util.List;


public record DeployInfoView(String deployedVersion, String deployedAt, Long deployedBy,
                             String deployedByName, List<DeployBatchRefView> batches) {
}
