package com.sixbean.testpike.core.document.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.document.api.dto.DocumentInfo;
import com.sixbean.testpike.core.document.api.dto.ExtractionItem;
import com.sixbean.testpike.core.document.api.dto.ExtractionPreview;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * AP-4(3) (core side): extraction items must carry the source anchor
 * pair — {@code evidence} (verbatim excerpt) plus {@code chunkId} (owning chunk) —
 * through writeExtractionResult, survive the idempotent retry merge for confirmed
 * items, and stay compatible with previews persisted before the fields existed
 * (tp_document.extraction_json rows without the new keys read as null anchors).
 * <p>Fixtures build their own rows and clean up by id — no TRUNCATE of shipped
 * tables (R37 baseline duty). The adopt-and-clean slot lives on the shared rig
 * so the whole extraction test family keeps one fixture lifecycle (R40).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentExtractionEvidenceAnchorTest {

    @Autowired
    private DocumentExtractionTestRig rig;

    @AfterEach
    void cleanUpFixtures() {
        rig.cleanDocs();
    }

    /** Upload a parseable MD doc (fixture single source, R40). */
    private long preparingDoc(String token) {
        ExtractionPreviewFixtures.Docs docs = ExtractionPreviewFixtures.preparingDoc(
                "锚点项目", rig.projectService, rig.documentService, rig.jdbcTemplate);
        rig.adoptDocs(docs);
        ExtractionPreviewFixtures.armExtracting(rig.jdbcTemplate, rig.docId, token);
        return rig.docId;
    }

    @Test
    void writeExtractionResultCarriesEvidenceAndChunkIdIntoPreview() {
        String token = UUID.randomUUID().toString();
        long id = preparingDoc(token);
        rig.extractionService.writeExtractionResult(id, List.of(new ExtractionItem(0,
                "库存不足下单失败提示", "提交订单返回库存不足人话", "下单流程", 0.9,
                false, null, "用户提交订单时库存不足应提示下单失败", 501L)), token);

        ExtractionPreview preview = rig.extractionService.extraction(id, 1, 10);
        assertEquals(1, preview.items().size());
        assertEquals("用户提交订单时库存不足应提示下单失败", preview.items().get(0).evidence());
        assertEquals(501L, preview.items().get(0).chunkId());
    }

    @Test
    void retryMergeKeepsAnchorOfConfirmedItemAndStoresAnchorOfNewItem() {
        String token1 = UUID.randomUUID().toString();
        long id = preparingDoc(token1);
        rig.extractionService.writeExtractionResult(id, List.of(new ExtractionItem(0,
                "库存不足下单失败提示", "提交订单返回库存不足人话", "下单流程", 0.9,
                true, 9001L, "已确认条目的原文摘录", 511L)), token1);

        String token2 = UUID.randomUUID().toString();
        rig.jdbcTemplate.update("UPDATE tp_document SET status=3, extract_token=? WHERE id=?",
                token2, id);
        rig.extractionService.writeExtractionResult(id, List.of(
                new ExtractionItem(0, "库存不足下单失败提示", null, null, null,
                        false, null, null, null),
                new ExtractionItem(0, "重复提交防重", "双击仅一单", "下单流程", 0.8,
                        false, null, "重复提交订单仅生成一单", 512L)), token2);

        ExtractionPreview preview = rig.extractionService.extraction(id, 1, 10);
        ExtractionItem kept = preview.items().stream()
                .filter(i -> "库存不足下单失败提示".equals(i.title())).findFirst().orElseThrow();
        assertEquals(9001L, kept.requirementId(), "已确认条目 requirementId 语义零破坏");
        assertEquals("已确认条目的原文摘录", kept.evidence(),
                "confirmed item keeps its original anchor when the retry lost it");
        assertEquals(511L, kept.chunkId());
        ExtractionItem fresh = preview.items().stream()
                .filter(i -> "重复提交防重".equals(i.title())).findFirst().orElseThrow();
        assertEquals("重复提交订单仅生成一单", fresh.evidence());
        assertEquals(512L, fresh.chunkId());
    }

    @Test
    void legacyPreviewJsonWithoutNewKeysReadsAsNullAnchors() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        rig.projectId = rig.projectService.create(1L,
                new ProjectCreateInput("旧档项目" + tag, tag, "zh-CN", "P1", null));
        rig.docId = rig.jdbcTemplate.queryForObject(
                "INSERT INTO tp_document (project_id, name, file_hash, doc_category, status,"
                        + " extraction_json, created_by, updated_at) VALUES (?, 'legacy.md',"
                        + " ?, 'TEST_SPEC', 4, ?::jsonb, 1, now()) RETURNING id",
                Long.class, rig.projectId, UUID.randomUUID().toString(),
                "[{\"id\":1,\"title\":\"旧条目\",\"acceptanceCriteria\":null,"
                        + "\"chapter\":null,\"confidence\":null,\"selected\":true,"
                        + "\"requirementId\":9002}]");

        ExtractionPreview preview = rig.extractionService.extraction(rig.docId, 1, 10);
        assertEquals(1, preview.items().size());
        assertNull(preview.items().get(0).evidence(), "legacy row → null evidence");
        assertNull(preview.items().get(0).chunkId(), "legacy row → null chunkId");
        assertEquals(9002L, preview.items().get(0).requirementId());
    }

    @Test
    void chunkListingExposesRowIdForSourceAttribution() {
        preparingDoc(UUID.randomUUID().toString());
        List<java.util.Map<String, Object>> chunks = rig.documentService.chunks(rig.docId);
        assertNotNull(chunks);
        assertEquals(1, chunks.size(), "fixture doc has one content chunk");
        assertNotNull(chunks.get(0).get("id"),
                "chunks() must expose tp_document_chunk.id so ai-service can anchor");
    }
}
