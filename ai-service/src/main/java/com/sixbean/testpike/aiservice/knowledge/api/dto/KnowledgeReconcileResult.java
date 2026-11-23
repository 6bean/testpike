package com.sixbean.testpike.aiservice.knowledge.api.dto;

import java.util.List;


public record KnowledgeReconcileResult(List<Long> repairedDocIds, List<Long> orphanDeletedDocIds,
                                       int ledgerActiveRows, int vectorRowsAfter) {

    
    public boolean clean() {
        return repairedDocIds.isEmpty() && orphanDeletedDocIds.isEmpty();
    }
}
