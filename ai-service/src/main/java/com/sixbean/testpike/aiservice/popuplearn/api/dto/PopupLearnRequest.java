package com.sixbean.testpike.aiservice.popuplearn.api.dto;

import java.util.List;


public record PopupLearnRequest(
        Long taskId,
        Long projectId,
        List<String> images) {
}
