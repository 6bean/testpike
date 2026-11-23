package com.sixbean.testpike.core.document.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.document.api.dto.ConfirmExtractionResult;
import com.sixbean.testpike.core.document.api.dto.ExtractionItem;
import com.sixbean.testpike.core.document.api.dto.ExtractionPreview;
import com.sixbean.testpike.core.document.domain.service.impl.DocumentExtractionServiceImpl;
import com.sixbean.testpike.core.support.SysConfigSwitchFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * both confirm doors share one evidence-anchor gate. With
 * ai.doc.anchor-required.enabled on (factory posture) a preview item whose
 * evidence never resolved to a source chunk is held back from landing, so
 * every requirement row minted by confirm keeps a non-empty chunkIds anchor
 * (the 100% contract: an injected anchorless preview row must turn red). An
 * explicit "false" restores the old landing posture for legacy previews.
 * <p>Runs against the real dev database — no mocks. Fixtures build their own
 * rows and sweep by id (R37: shipped baseline tables are never truncated);
 * the switch row is factory seed, so cleanup only restores the shipped value
 * and description, never deletes the row.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class DocumentExtractionConfirmAnchorGateTest {

    @Autowired
    private DocumentExtractionTestRig rig;
    @Autowired
    private ConfigService configService;

    /** Same literal as the V466 factory seed — a fixture reset must never
     *  rewrite the shipped description into a second on-screen variant. */
    private static final String ANCHOR_SWITCH_DESCRIPTION =
            "证据锚齐全开关（默认开）。开启时，证据摘录回指不到原文块的需求点不会入库，"
                    + "拦截条数会在提取过程详情中点名；关闭后所有需求点照常入库。保存即生效，无需重启";

    @AfterEach
    void cleanUpFixtures() {
        rig.cleanDocs();
        upsertSwitch("true");
    }

    /** Service write path via the shared fixture (R40): the in-memory config
     *  cache syncs only through the write side, never raw SQL. */
    private void upsertSwitch(String value) {
        SysConfigSwitchFixture.upsert(rig.jdbcTemplate, configService,
                DocumentExtractionServiceImpl.KEY_ANCHOR_REQUIRED, value,
                "系统", "string", ANCHOR_SWITCH_DESCRIPTION);
    }

    private void preparingExtractedDoc(List<ExtractionItem> items) {
        String token = UUID.randomUUID().toString();
        ExtractionPreviewFixtures.Docs docs = ExtractionPreviewFixtures.preparingDoc(
                "证据锚确认闸项目", rig.projectService, rig.documentService, rig.jdbcTemplate);
        rig.adoptDocs(docs);
        ExtractionPreviewFixtures.armExtracting(rig.jdbcTemplate, docs.docId(), token);
        rig.extractionService.writeExtractionResult(docs.docId(), items, token);
    }

    private int landedRowCount() {
        Integer rows = rig.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_requirement WHERE source_id = ?",
                Integer.class, "doc:" + rig.docId);
        return rows == null ? 0 : rows;
    }

    /** The full-store door: an anchored item lands with its chunkIds written
     *  through to tp_requirement.source_anchor; an injected anchorless item
     *  never lands (the 100% non-empty anchor contract turns red otherwise). */
    @Test
    void confirmAllRefusesPlantedAnchorlessItemAndKeepsAnchorComplete() {
        preparingExtractedDoc(List.of(
                new ExtractionItem(0, "锚定需求点甲", "验收甲", "章一", 0.9, false, null,
                        "逐字摘录甲", 4001L),
                new ExtractionItem(0, "植入无锚需求点乙", "验收乙", "章一", 0.9, false, null)));

        ConfirmExtractionResult result = rig.extractionService.confirmAllExtraction(1L, rig.docId);

        assertEquals(1, result.createdCount(), "only the anchored item lands");
        assertEquals(1, result.skipped(), "the anchorless item is held back as a skip");
        assertEquals(1, landedRowCount(), "no second row behind the reviewer's back");
        Integer withNonEmptyAnchor = rig.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_requirement WHERE source_id = ? "
                        + "AND jsonb_array_length(source_anchor -> 'chunkIds') > 0",
                Integer.class, "doc:" + rig.docId);
        assertEquals(1, withNonEmptyAnchor,
                "every landed row keeps a non-empty chunkIds anchor (100% contract)");
        Integer heldRows = rig.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_requirement WHERE source_id = ? AND title = ?",
                Integer.class, "doc:" + rig.docId, "植入无锚需求点乙");
        assertEquals(0, heldRows,
                "an injected anchorless preview row must never become a requirement");
    }

    /** Both doors delegate to the same transaction, so the checked door holds
     *  the anchorless item through the very same single-source gate. */
    @Test
    void checkedDoorRefusesAnchorlessItemThroughTheSameGate() {
        preparingExtractedDoc(List.of(
                new ExtractionItem(0, "勾选门锚定需求点", "验收甲", "章一", 0.9, false, null,
                        "逐字摘录甲", 4002L),
                new ExtractionItem(0, "勾选门无锚需求点", "验收乙", "章一", 0.9, false, null)));
        ExtractionPreview preview = rig.extractionService.extraction(rig.docId, 1, 10);

        ConfirmExtractionResult result = rig.extractionService.confirmExtraction(
                1L, rig.docId, List.copyOf(preview.items()));

        assertEquals(1, result.createdCount(), "the anchored checked item lands");
        assertEquals(1, result.skipped(), "the anchorless checked item is held back");
        assertEquals(1, landedRowCount());
    }

    /** Explicit off = old posture: the anchorless item lands again and its
     *  anchor stays an empty chunkIds array (zero-drift rollback face). */
    @Test
    void explicitGateOffLandsAnchorlessItemForLegacyPreviews() {
        upsertSwitch("false");
        preparingExtractedDoc(List.of(
                new ExtractionItem(0, "关态锚定需求点", "验收甲", "章一", 0.9, false, null,
                        "逐字摘录甲", 4003L),
                new ExtractionItem(0, "关态无锚需求点", "验收乙", "章一", 0.9, false, null)));

        ConfirmExtractionResult result = rig.extractionService.confirmAllExtraction(1L, rig.docId);

        assertEquals(2, result.createdCount(), "gate off restores the legacy landing face");
        assertEquals(0, result.skipped());
        assertEquals(2, landedRowCount());
        Integer emptyAnchorRows = rig.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_requirement WHERE source_id = ? AND title = ? "
                        + "AND coalesce(jsonb_array_length(source_anchor -> 'chunkIds'), 0) = 0",
                Integer.class, "doc:" + rig.docId, "关态无锚需求点");
        assertNotNull(emptyAnchorRows);
        assertEquals(1, emptyAnchorRows,
                "the held-back item now lands with an empty chunkIds array as before");
    }
}
