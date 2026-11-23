package com.sixbean.testpike.core.knowledge.api.dto;

import java.util.List;


public record KnowledgeHitInput(Long docId, Long taskId, String scene, List<String> chunkIds) {
}
