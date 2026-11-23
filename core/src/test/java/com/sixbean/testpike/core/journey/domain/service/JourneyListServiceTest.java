package com.sixbean.testpike.core.journey.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.journey.api.dto.JourneyCreateInput;
import com.sixbean.testpike.core.journey.api.dto.JourneyPageView;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.sixbean.testpike.core.support.JourneyFixtureCleaner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Journey wall listing against the real chain database: the page
 * window is program-scoped, newest first, carries the whole filtered total and an
 * optional case-insensitive name filter, and every bad window argument fails before
 * any read. Rows are header-only — the bounded list query must never fan out into
 * segment loading (worst-scale contract).
 * <p>tp_journey* tables carry no factory rows; every journey row a test creates
 * under its tracked programs is purged by lineage, programs by id afterwards
 * (R37 — shared tables are never truncated, concurrent rows stay untouched).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class JourneyListServiceTest {

    @Autowired
    private JourneyService journeyService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JourneyFixtureCleaner journeyLedger;

    @BeforeEach
    @AfterEach
    void cleanJourneyTables() {
        journeyLedger = JourneyFixtureCleaner.forTestRun(journeyLedger, jdbcTemplate, projectService);
        journeyLedger.teardownSuiteTables();
    }

    private long createJourney(long programId, String name) {
        return journeyService.createJourney(1L, new JourneyCreateInput(programId, name, 7L)).id();
    }

    @Test
    void pageJourneys_returns_newest_first_program_scoped_window_with_total() {
        long programA = journeyLedger.createProgram("jlist-");
        long programB = journeyLedger.createProgram("jlist-");
        createJourney(programA, "alpha");
        createJourney(programA, "beta");
        long gamma = createJourney(programA, "gamma");
        createJourney(programB, "other-program");

        JourneyPageView firstPage = journeyService.pageJourneys(programA, 1, 2, null);
        assertEquals(3L, firstPage.total(), "total counts the whole filtered set, not the window");
        assertEquals(2, firstPage.list().size());
        assertEquals(gamma, firstPage.list().get(0).id(), "newest first");
        assertTrue(firstPage.list().get(0).segments().isEmpty(),
                "page rows carry the header only — segments stay behind the aggregate read");

        JourneyPageView overflow = journeyService.pageJourneys(programA, 3, 2, null);
        assertEquals(3L, overflow.total());
        assertTrue(overflow.list().isEmpty(), "page past the end returns an empty window, not an error");
    }

    @Test
    void pageJourneys_keyword_filters_by_name_containing_case_insensitive() {
        long program = journeyLedger.createProgram("jlist-");
        createJourney(program, "Alpha");
        createJourney(program, "beta");

        JourneyPageView hit = journeyService.pageJourneys(program, 1, 20, "  BE ");
        assertEquals(1L, hit.total());
        assertEquals("beta", hit.list().get(0).name());
    }

    @Test
    void pageJourneys_rejects_bad_window_arguments_before_any_read() {
        assertThrows(ServiceException.class, () -> journeyService.pageJourneys(0, 1, 20, null));
        assertThrows(ServiceException.class, () -> journeyService.pageJourneys(1, 0, 20, null));
        assertThrows(ServiceException.class, () -> journeyService.pageJourneys(1, 1, 0, null));
    }
}
