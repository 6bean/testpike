package com.sixbean.testpike.core.knowledge.api.dto;

import java.util.List;


public record KnowledgeHitItem(Long id, Long taskId, String taskTitle, String scene,
                               List<String> chunkIds, String createdAt) {
}
