package com.sixbean.testpike.aiservice.knowledge.domain.component;

import org.springframework.ai.document.Document;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


public final class KnowledgeChunkDocumentFactory {

    private KnowledgeChunkDocumentFactory() {
    }

    
    public static Document create(long docId, long projectId, int seq, String title,
                                  String maskedContent, String lang,
                                  String sourceType, long caseId, boolean excellent) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(KnowledgeMetadataFields.DOC_ID, docId);
        metadata.put(KnowledgeMetadataFields.PROJECT_ID, projectId);
        metadata.put(KnowledgeMetadataFields.PACK_ID, 0L);
        metadata.put(KnowledgeMetadataFields.SOURCE_TYPE, sourceType);
        metadata.put(KnowledgeMetadataFields.CASE_ID, caseId);
        metadata.put(KnowledgeMetadataFields.CHUNK_INDEX, seq);
        metadata.put(KnowledgeMetadataFields.TITLE, title);
        metadata.put(KnowledgeMetadataFields.LANG, lang);
        metadata.put(KnowledgeMetadataFields.EXCELLENT, excellent);
        metadata.put(KnowledgeMetadataFields.STATUS, KnowledgeMetadataFields.ACTIVE);
        return Document.builder()
                .id(chunkVectorId(docId, seq).toString())
                .text(maskedContent)
                .metadata(metadata)
                .build();
    }

    
    public static UUID chunkVectorId(long docId, int seq) {
        return UUID.nameUUIDFromBytes(
                ("tp-kb:" + docId + ":" + seq).getBytes(StandardCharsets.UTF_8));
    }
}
