package com.sixbean.testpike.core.document.api.dto;

/**
 *
 * @param fileType pdf / markdown
 * @param category doc category (TEST_SPEC default / DDL_DICT dictionary /
 * REFERENCE pure-knowledge; edited in place via PATCH under the row lock)
 * @param useForExtraction may this document enter the requirement-extraction queue
 * @param useInCasePrompt may this document feed the case-generation prompt context
 * @param useInGrounding may this document feed chat grounding retrieval
 * @param revision row-level optimistic-lock token (bumped on every successful edit)
 * @param lineageGroupId lineage group root id — rows sharing the same logical
 * document (project + display name + file type) carry one group id; the
 * sha256 duplicate gate and the auto re-upload diff pairing are scoped
 * by this group
 * @param lineageSeq 1-based ordinal of this row inside its lineage group
 * (ordered by upload id; null = ungrouped row — server single source,
 * the list surface must not re-derive it on the client)
 * @param lineageSize total versions in the lineage group (null = ungrouped;
 * 1 = first and only version)
 */
public record DocumentInfo(Long id, Long projectId, String name, String fileType,
                           String fileHash, Long sizeBytes, String storageKey,
                           int status, int chunkCount, String errorMsg, String category,
                           boolean useForExtraction, boolean useInCasePrompt,
                           boolean useInGrounding, int revision, Long lineageGroupId,
                           Integer lineageSeq, Integer lineageSize) {
}
