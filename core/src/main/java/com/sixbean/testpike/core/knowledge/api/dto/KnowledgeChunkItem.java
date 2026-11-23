package com.sixbean.testpike.core.knowledge.api.dto;

import java.util.Map;


public record KnowledgeChunkItem(String chunkId, int chunkIndex, String content,
                                 Map<String, Object> metadata) {
}
