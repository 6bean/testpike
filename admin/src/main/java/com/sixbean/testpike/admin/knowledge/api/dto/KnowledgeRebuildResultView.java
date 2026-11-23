package com.sixbean.testpike.admin.knowledge.api.dto;

import java.util.List;


public record KnowledgeRebuildResultView(List<Long> repairedDocIds, List<Long> orphanDeletedDocIds,
                                         int ledgerActiveRows, int vectorRowsAfter) {
}
