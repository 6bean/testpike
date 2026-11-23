package com.sixbean.testpike.core.document.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.document.domain.service.impl.DocumentExtractionServiceImpl;
import com.sixbean.testpike.core.support.SysConfigSwitchFixture;
import com.sixbean.testpike.core.document.api.dto.ConfirmExtractionResult;
import com.sixbean.testpike.core.document.api.dto.ExtractionItem;
import com.sixbean.testpike.core.document.api.dto.ExtractionPreview;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full-store confirm door (core side, : the server enumerates the
 * whole extraction preview itself, so a paged viewer can never cap how many
 * requirement points land. The door delegates to the very same tri-state
 * confirm transaction as the checked door — dedup cards, idempotent skips and
 * the evidence-anchor write-through are asserted here through that single
 * source, never through a second implementation.
 * <p>Fixtures build their own rows and clean up by id (R37: shipped baseline
 * tables are never truncated). The adopt-and-clean slot lives on the shared
 * rig so the whole extraction test family keeps one fixture lifecycle (R40).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentExtractionConfirmAllTest {

    @Autowired
    private DocumentExtractionTestRig rig;
    @Autowired
    private ConfigService configService;

    /** Same literal as the V466 factory seed - a fixture reset must never
     *  rewrite the shipped description into a second on-screen variant. */
    private static final String ANCHOR_SWITCH_DESCRIPTION =
            "证据锚齐全开关（默认开）。开启时，证据摘录回指不到原文块的需求点不会入库，"
                    + "拦截条数会在提取过程详情中点名；关闭后所有需求点照常入库。保存即生效，无需重启";

    /** recalibration: these fixtures build anchorless previews
 * and exercise the confirm bookkeeping (full-store enumeration, replay
 * idempotence, door coexistence) - not the anchor policy, which has its
 * own face in DocumentExtractionConfirmAnchorGateTest. The gate is
 * explicitly off here (the posture these fixtures predate) and restored
 * to factory on after each test. */
    @BeforeEach
    void anchorGateOffForLegacyFixtures() {
        SysConfigSwitchFixture.upsert(rig.jdbcTemplate, configService,
                DocumentExtractionServiceImpl.KEY_ANCHOR_REQUIRED, "false",
                "系统", "string", ANCHOR_SWITCH_DESCRIPTION);
    }

    @AfterEach
    void cleanUpFixtures() {
        rig.cleanDocs();
        SysConfigSwitchFixture.upsert(rig.jdbcTemplate, configService,
                DocumentExtractionServiceImpl.KEY_ANCHOR_REQUIRED, "true",
                "系统", "string", ANCHOR_SWITCH_DESCRIPTION);
    }

    /** Preview with {@code count} distinct unconfirmed items, status landed at 4. */
    private void preparingExtractedDoc(int count) {
        String token = UUID.randomUUID().toString();
        ExtractionPreviewFixtures.Docs docs = ExtractionPreviewFixtures.preparingDoc(
                "全量入库项目", rig.projectService, rig.documentService, rig.jdbcTemplate);
        rig.adoptDocs(docs);
        ExtractionPreviewFixtures.armExtracting(rig.jdbcTemplate, rig.docId, token);
        List<ExtractionItem> items = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            items.add(new ExtractionItem(0,
                    String.format("全量入库需求点%02d", i),
                    "验收标准" + i, "章节" + (i % 5 + 1), 0.9, false, null));
        }
        rig.extractionService.writeExtractionResult(rig.docId, items, token);
    }

    private long landedRequirementRows() {
        Long rows = rig.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_requirement WHERE source_id = ?",
                Long.class, "doc:" + rig.docId);
        return rows == null ? 0L : rows;
    }

    /** The full-store door ignores pagination: every preview item lands once. */
    @Test
    void confirmAllLandsEveryPreviewItemAcrossPagingBoundaries() {
        preparingExtractedDoc(25);

        ConfirmExtractionResult result = rig.extractionService.confirmAllExtraction(1L, rig.docId);

        assertEquals(25, result.createdCount(), "all 25 preview items land, page size caps nothing");
        assertEquals(0, result.skipped());
        assertEquals(0, result.drafted());
        assertEquals(25, result.createdRequirementIds().size(),
                "landed id list travels back for the generation dispatch (T538 slice-0 shape)");
        assertEquals(25, new HashSet<>(result.createdRequirementIds()).size(),
                "one distinct requirement id per item");
        assertEquals(25L, landedRequirementRows(), "rows in tp_requirement match the result");
    }

    /** Idempotent replay: every item already carries its requirement id → nothing new lands. */
    @Test
    void confirmAllReplayLandsNothingNew() {
        preparingExtractedDoc(25);
        assertEquals(25, rig.extractionService.confirmAllExtraction(1L, rig.docId).createdCount());

        ConfirmExtractionResult replay = rig.extractionService.confirmAllExtraction(1L, rig.docId);

        assertEquals(0, replay.createdCount(), "replay must never mint a second row");
        assertEquals(25, replay.skipped(), "anchored items count as skipped on replay");
        assertEquals(0, replay.drafted());
        assertTrue(replay.createdRequirementIds().isEmpty(), "no new ids on a null-op replay");
        assertEquals(25L, landedRequirementRows(), "row count stays 25 after the replay");
    }

    /** Two doors coexist: the checked door lands its subset first, the full-store door completes the rest. */
    @Test
    void checkedDoorFirstThenConfirmAllCompletesRemainder() {
        preparingExtractedDoc(25);
        ExtractionPreview preview = rig.extractionService.extraction(rig.docId, 1, 10);
        List<ExtractionItem> checked = new ArrayList<>(preview.items().subList(0, 3));

        ConfirmExtractionResult first = rig.extractionService.confirmExtraction(1L, rig.docId, checked);
        ConfirmExtractionResult rest = rig.extractionService.confirmAllExtraction(1L, rig.docId);

        assertEquals(3, first.createdCount(), "checked door lands exactly the selected subset");
        assertEquals(22, rest.createdCount(),
                "full-store door completes only the remaining items, not the already-landed three");
        assertEquals(3, rest.skipped(), "already-landed items are idempotent skips, never re-created");
        assertEquals(25L, landedRequirementRows(), "both doors together land the whole preview once");
    }

    /** State guard: a doc that has not finished extraction offers nothing to store. */
    @Test
    void confirmAllBeforeExtractionFinishedIsRejected() {
        String token = UUID.randomUUID().toString();
        ExtractionPreviewFixtures.Docs docs = ExtractionPreviewFixtures.preparingDoc(
                "全量入库项目", rig.projectService, rig.documentService, rig.jdbcTemplate);
        rig.adoptDocs(docs);
        ExtractionPreviewFixtures.armExtracting(rig.jdbcTemplate, rig.docId, token);

        ServiceException rejected = assertThrows(ServiceException.class,
                () -> rig.extractionService.confirmAllExtraction(1L, rig.docId));
        assertTrue(rejected.getMessage().contains("文档尚未完成提取"),
                "rejection names the state in operator-facing words: " + rejected.getMessage());
        assertEquals(0L, landedRequirementRows(), "rejected door lands nothing");
    }
}
