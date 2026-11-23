package com.sixbean.testpike.admin.ai.api.dto;

/**
 *
 * @param fileType pdf / markdown
 * @param category doc category (TEST_SPEC / DDL_DICT / REFERENCE, edited via PATCH)
 * @param useForExtraction per-document extraction-participation switch
 * @param useInCasePrompt per-document case-prompt participation switch
 * @param useInGrounding per-document grounding participation switch
 * @param revision row-level optimistic-lock token (required on every edit call)
 * @param lineageGroupId lineage group root id (same group = same logical document,
 * different uploaded versions; null = ungrouped legacy row)
 * @param lineageSeq 1-based in-group version ordinal computed server-side
 * (null = ungrouped; the UI renders this value, never re-derives it)
 * @param lineageSize total versions in the lineage group (null = ungrouped)
 */
public record DocumentInfoView(Long id, Long projectId, String name, String fileType,
                               String fileHash, Long sizeBytes, String storageKey,
                               int status, int chunkCount, String errorMsg, String category,
                               boolean useForExtraction, boolean useInCasePrompt,
                               boolean useInGrounding, int revision,
                               Long lineageGroupId, Integer lineageSeq, Integer lineageSize) {
}
