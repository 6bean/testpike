package com.sixbean.testpike.core.journey.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.journey.api.dto.JourneyCreateInput;
import com.sixbean.testpike.core.journey.api.dto.JourneyInfo;
import com.sixbean.testpike.core.journey.api.dto.JourneySegmentInfo;
import com.sixbean.testpike.core.journey.api.dto.JourneySegmentInput;
import com.sixbean.testpike.core.journey.domain.service.impl.JourneyServiceImpl;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import com.sixbean.testpike.core.support.JourneyFixtureCleaner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Journey plan surface against the real chain database: a journey is
 * created under an active program with a sole owner, segments are appended in unique
 * order, and an asset-target segment registers its reference pin on the shared asset
 * pool with host kind "journey_segment" through SharedAssetReferenceService — the same
 * single source the asset pool's "who uses me" view reads, so no second protocol exists.
 * Unknown assets and duplicate orders are rejected before any write.
 * <p>tp_journey* tables carry no factory rows; every journey row, pin and asset
 * a test creates is purged by lineage and tracked id around each test (R37 —
 * shared tables are never truncated, concurrent rows stay untouched).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class JourneySegmentPinServiceTest {

    @Autowired
    private JourneyService journeyService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JourneyFixtureCleaner pinLedger;

    @BeforeEach
    @AfterEach
    void cleanJourneyTables() {
        pinLedger = JourneyFixtureCleaner.forTestRun(pinLedger, jdbcTemplate, projectService);
        pinLedger.teardownSuiteTables();
    }

    private long createProject() {
        String code = "j" + UUID.randomUUID().toString().substring(0, 10);
        long id = projectService.create(1L,
                new ProjectCreateInput("JourneyHost " + code, code, "zh-CN", "P1", null));
        pinLedger.trackProject(id);
        return id;
    }

    private long publishAsset(long ownerProjectId, String assetName) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_asset (project_id, kind, name, payload_json) VALUES ("
                        + ownerProjectId + ", 'case_fragment', '" + assetName
                        + "', '{\"steps\":[]}') RETURNING id", Long.class);
        pinLedger.trackAsset(id);
        return id;
    }

    @Test
    void createJourneyUnderActiveProgramReadsBackAsEmptyPlan() {
        long programId = pinLedger.createProgram("jprog-");
        JourneyInfo created = journeyService.createJourney(1L,
                new JourneyCreateInput(programId, "release-day journey", 1L));
        assertTrue(created.id() > 0);
        JourneyInfo read = journeyService.getJourney(created.id());
        assertEquals(programId, read.programId());
        assertEquals("release-day journey", read.name());
        assertEquals(1, read.status());
        assertEquals(1L, read.ownerUserId());
        assertEquals(List.of(), read.segments());
    }

    @Test
    void assetSegmentRegistersPinOnSharedReferenceSource() {
        long programId = pinLedger.createProgram("jprog-");
        long assetProject = createProject();
        long consumerProject = createProject();
        long assetRowId = publishAsset(assetProject, "login-recipe-" + UUID.randomUUID());
        long journeyId = journeyService.createJourney(1L,
                new JourneyCreateInput(programId, "pinned segments", 1L)).id();

        JourneySegmentInfo segment = journeyService.addSegment(journeyId,
                new JourneySegmentInput(1, consumerProject, "asset", assetRowId, null, null));
        assertEquals("asset", segment.targetKind());
        assertEquals(assetRowId, segment.assetRowId());

        Integer pins = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_asset_reference WHERE referrer_kind = ? "
                        + "AND referrer_id = ? AND asset_row_id = ? AND referrer_project_id = ?",
                Integer.class, JourneyServiceImpl.SEGMENT_PIN_KIND, segment.id(), assetRowId,
                consumerProject);
        assertEquals(1, pins,
                "an asset segment must register exactly one pin through the shared reference source");
    }

    @Test
    void unknownAssetRowIsRejectedBeforeAnySegmentWrite() {
        long programId = pinLedger.createProgram("jprog-");
        long consumerProject = createProject();
        long journeyId = journeyService.createJourney(1L,
                new JourneyCreateInput(programId, "bad target", 1L)).id();

        assertThrows(ServiceException.class, () -> journeyService.addSegment(journeyId,
                new JourneySegmentInput(1, consumerProject, "asset", 999_999_999L, null, null)));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_journey_segment WHERE journey_id = " + journeyId,
                Integer.class));
    }

    @Test
    void duplicateSegmentOrderIsRejected() {
        long programId = pinLedger.createProgram("jprog-");
        long assetProject = createProject();
        long consumerProject = createProject();
        long assetRowId = publishAsset(assetProject, "dup-" + UUID.randomUUID());
        long journeyId = journeyService.createJourney(1L,
                new JourneyCreateInput(programId, "dup seq", 1L)).id();
        journeyService.addSegment(journeyId,
                new JourneySegmentInput(2, consumerProject, "asset", assetRowId, null, null));

        assertThrows(ServiceException.class, () -> journeyService.addSegment(journeyId,
                new JourneySegmentInput(2, consumerProject, "case", null, 42L, null)));
        JourneyInfo read = journeyService.getJourney(journeyId);
        assertEquals(1, read.segments().size());
    }
}
