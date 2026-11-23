package com.sixbean.testpike.admin.knowledge.api.dto;

import java.util.List;


public record KnowledgeHitView(Long id, Long taskId, String taskTitle, String scene,
                               List<String> chunkIds, String createdAt) {
}
