package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;


public record AppautoPopupLearnRequest(
        Long taskId,
        Long projectId,
        List<String> images) {
}
