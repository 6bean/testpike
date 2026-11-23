package com.sixbean.testpike.aiservice.gen.infrastructure.remote;

import java.util.List;


public record KnowledgeHitRemoteInput(
        Long docId,
        Long taskId,
        String scene,
        List<String> chunkIds) {
}
