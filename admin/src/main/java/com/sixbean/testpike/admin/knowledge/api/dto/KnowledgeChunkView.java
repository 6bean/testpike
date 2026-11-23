package com.sixbean.testpike.admin.knowledge.api.dto;

import java.util.Map;


public record KnowledgeChunkView(String chunkId, int chunkIndex, String content,
                                 Map<String, Object> metadata) {
}
