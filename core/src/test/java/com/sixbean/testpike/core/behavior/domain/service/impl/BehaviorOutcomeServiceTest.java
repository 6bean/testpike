package com.sixbean.testpike.core.behavior.domain.service.impl;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.behavior.api.dto.BehaviorOutcomeAdvisory;
import com.sixbean.testpike.core.behavior.api.dto.BehaviorOutcomeEvent;
import com.sixbean.testpike.core.behavior.api.dto.BehaviorOutcomeStat;
import com.sixbean.testpike.core.behavior.domain.service.BehaviorOutcomeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavior outcome ledger contract : every real user
 * verdict — review pass/reject, verification-chip decision, draft accept/reject —
 * lands in tp_ai_behavior_outcome exactly once and rolls up into a per-template
 * version result rate that drives a rollback advisory (advisory only; humans stay
 * in the loop). Runs against the real dev DB of this chain's slot (strict testing:
 * no mocks on service interfaces). Test rows are tagged 'ut-behavior-' and removed
 * per case (R37: no TRUNCATE of any factory table).
 */
@SpringBootTest(classes = CoreApplication.class)
class BehaviorOutcomeServiceTest {

    private static final String TAG = "ut-behavior-";
    private static final String DOMAIN = "ut-behavior-domain";
    private static final String OTHER_DOMAIN = "ut-behavior-other";
    private static final long CONTROL_VERSION = 9_000_101L;
    private static final long CANDIDATE_VERSION = 9_000_102L;

    @Autowired
    private BehaviorOutcomeService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void adoptionSourceIsAcceptedByTheSharedLedger() {
        // piece 1: derived adoption forms ride the same table as
        // verdicts (R40 single source) — the DB CHECK must carry the new tag,
        // and replays of one adoption observation must count exactly once.
        String eventId = TAG + "adoption-1";
        assertTrue(service.record(new BehaviorOutcomeEvent(DOMAIN, null, "task",
                "ref-" + eventId, BehaviorOutcomeServiceImpl.SOURCE_ADOPTION, eventId,
                "partial", new BigDecimal("1.0"), 9_999_999L)));
        assertFalse(service.record(new BehaviorOutcomeEvent(DOMAIN, null, "task",
                "ref-" + eventId, BehaviorOutcomeServiceImpl.SOURCE_ADOPTION, eventId,
                "partial", new BigDecimal("1.0"), 9_999_999L)));
        Integer kept = jdbc.queryForObject(
                "SELECT count(*) FROM tp_ai_behavior_outcome WHERE source = 'adoption' AND source_event_id = ?",
                Integer.class, eventId);
        assertEquals(1, kept, "one adoption observation counts exactly once");
    }

    @AfterEach
    void cleanUpTestRows() {
        jdbc.update("DELETE FROM tp_ai_behavior_outcome WHERE source_event_id LIKE ?", TAG + "%");
    }

    private BehaviorOutcomeEvent event(String source, String eventId, String scope,
                                       String outcome, Long versionId, String weight) {
        return new BehaviorOutcomeEvent(DOMAIN, versionId, scope, "ref-" + eventId,
                source, eventId, outcome, new BigDecimal(weight), 9_999_999L);
    }

    @Test
    void recordingSameSourceEventTwiceCountsExactlyOnce() {
        boolean first = service.record(event("review", TAG + "dup-1", "task", "adopted", CONTROL_VERSION, "1"));
        boolean replay = service.record(event("review", TAG + "dup-1", "task", "deprecated", CONTROL_VERSION, "1"));
        assertTrue(first, "first sighting of a label event must be recorded");
        assertFalse(replay, "replaying the same source event must be a no-op");
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM tp_ai_behavior_outcome WHERE source = 'review' AND source_event_id = ?",
                Integer.class, TAG + "dup-1");
        assertEquals(1, rows, "idempotent replay must leave exactly one row");
    }

    @Test
    void malformedEventsAreRejectedBeforeHittingTheTable() {
        assertThrows(ValidationException.class,
                () -> service.record(event("review", TAG + "bad-1", "conversation", "adopted", CONTROL_VERSION, "1")));
        assertThrows(ValidationException.class,
                () -> service.record(event("review", TAG + "bad-2", "task", "accepted", CONTROL_VERSION, "1")));
        assertThrows(ValidationException.class,
                () -> service.record(event("email", TAG + "bad-3", "task", "adopted", CONTROL_VERSION, "1")));
        assertThrows(ValidationException.class,
                () -> service.record(new BehaviorOutcomeEvent(DOMAIN, CONTROL_VERSION, "task",
                        null, "review", " ", "adopted", BigDecimal.ONE, 1L)));
        assertThrows(ValidationException.class,
                () -> service.record(event("review", TAG + "bad-4", "task", "adopted", CONTROL_VERSION, "0")));
    }

    @Test
    void statsBucketPerTemplateVersionAndStayInsideOneDomain() {
        service.record(event("review", TAG + "s-a1", "task", "adopted", CONTROL_VERSION, "1"));
        service.record(event("draft", TAG + "s-a2", "session", "adopted", CONTROL_VERSION, "1"));
        service.record(event("chip", TAG + "s-a3", "document", "deprecated", CONTROL_VERSION, "1"));
        service.record(event("chip", TAG + "s-b1", "document", "partial", CANDIDATE_VERSION, "1"));
        service.record(event("review", TAG + "s-b2", "task", "corrected", CANDIDATE_VERSION, "1"));
        service.record(new BehaviorOutcomeEvent(OTHER_DOMAIN, CONTROL_VERSION, "task",
                "ref-" + TAG + "s-c1", "review", TAG + "s-c1", "adopted", BigDecimal.ONE, 9_999_999L));

        List<BehaviorOutcomeStat> stats = service.statsByTemplateVersion(DOMAIN);
        assertEquals(2, stats.size(), "only the requested domain's versions are reported");
        BehaviorOutcomeStat control = stats.stream()
                .filter(s -> s.templateVersionId() == CONTROL_VERSION).findFirst().orElseThrow();
        BehaviorOutcomeStat candidate = stats.stream()
                .filter(s -> s.templateVersionId() == CANDIDATE_VERSION).findFirst().orElseThrow();
        assertEquals(3, control.eventCount());
        assertEquals(0, new BigDecimal("0.6667").compareTo(control.resultRate()),
                "weighted result rate = adopted-family credit / total weight");
        assertEquals(2, candidate.eventCount());
        assertEquals(0, new BigDecimal("0.5000").compareTo(candidate.resultRate()),
                "partial and corrected each earn half credit");
    }

    @Test
    void candidateDegradedBeyondThresholdRaisesRollbackAdvisory() {
        service.record(event("review", TAG + "ab-ctl", "task", "adopted", CONTROL_VERSION, "1"));
        service.record(event("draft", TAG + "ab-cnd", "session", "deprecated", CANDIDATE_VERSION, "1"));
        BehaviorOutcomeAdvisory advisory = service.compareVersions(DOMAIN, CONTROL_VERSION, CANDIDATE_VERSION);
        assertNotNull(advisory);
        assertEquals(0, new BigDecimal("1.0000").compareTo(advisory.controlRate()));
        assertEquals(0, new BigDecimal("0.0000").compareTo(advisory.candidateRate()));
        assertTrue(advisory.rollbackAdvisory(),
                "a 100pp drop is far beyond the shipped 10pp default and must advise");
        assertTrue(advisory.thresholdPercentagePoints() >= 1 && advisory.thresholdPercentagePoints() <= 50,
                "threshold always resolves into the declared range even when the seed row is absent");
        assertNotNull(advisory.note(), "the advisory row must carry a human-readable reason");
    }

    @Test
    void smallDipInsideThresholdKeepsCandidateSilent() {
        // control: 20 adopted -> 1.0 ; candidate: 19 adopted + 1 deprecated -> 0.95 (drop 5pp < 10pp)
        for (int i = 0; i < 20; i++) {
            service.record(event("review", TAG + "ok-c" + i, "task", "adopted", CONTROL_VERSION, "1"));
        }
        for (int i = 0; i < 19; i++) {
            service.record(event("review", TAG + "ok-k" + i, "task", "adopted", CANDIDATE_VERSION, "1"));
        }
        service.record(event("review", TAG + "ok-k20", "task", "deprecated", CANDIDATE_VERSION, "1"));
        BehaviorOutcomeAdvisory advisory = service.compareVersions(DOMAIN, CONTROL_VERSION, CANDIDATE_VERSION);
        assertFalse(advisory.rollbackAdvisory(),
                "5pp is inside the default 10pp tolerance and must not nag");
        assertEquals(0, new BigDecimal("5.00").compareTo(advisory.dropPercentagePoints()));
    }

    @Test
    void thresholdParsingFallsBackToShippedDefaultOutOfRange() {
        assertEquals(10, BehaviorOutcomeServiceImpl.parseThreshold("999"));
        assertEquals(10, BehaviorOutcomeServiceImpl.parseThreshold("0"));
        assertEquals(10, BehaviorOutcomeServiceImpl.parseThreshold("-3"));
        assertEquals(10, BehaviorOutcomeServiceImpl.parseThreshold("not-a-number"));
        assertEquals(10, BehaviorOutcomeServiceImpl.parseThreshold(null));
        assertEquals(50, BehaviorOutcomeServiceImpl.parseThreshold("50"));
        assertEquals(1, BehaviorOutcomeServiceImpl.parseThreshold("1"));
        assertEquals(25, BehaviorOutcomeServiceImpl.parseThreshold("25"));
    }

    @Test
    void comparingVersionsWithoutAnyOutcomeDataIsHonestNotFabricated() {
        BehaviorOutcomeAdvisory advisory = service.compareVersions(DOMAIN, 9_000_201L, 9_000_202L);
        assertFalse(advisory.rollbackAdvisory(), "no data can never mean 'degrading'");
        assertNotNull(advisory.note(), "the note must say the comparison was skipped for lack of data");
    }
}
