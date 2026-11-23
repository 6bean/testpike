package com.sixbean.testpike.aiservice.aichat.domain.component;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Goal-plan ledger storage contract : the first ledger
 * of a (session, plan_version) inserts the canonical step form; a replayed
 * identical set (order-insensitive, same semantics as
 * {@link GoalPlanLedger#sameSet}) writes nothing and leaves the revision
 * untouched; a real change updates the steps and bumps the revision;
 * unparsable input never reaches the database; an anchor-less done step is
 * demoted BEFORE persisting; the read door is fail-soft (a database outage
 * reads as "no ledger", never a thrown dialog turn).
 */
class GoalPlanLedgerStoreTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final GoalPlanLedgerStore underTest = new GoalPlanLedgerStore(jdbc);

    private static final String STEPS =
            "[{\"title\":\"生成用例\",\"state\":\"pending\",\"evidence\":\"\"},"
                    + "{\"title\":\"收集需求\",\"state\":\"done\",\"evidence\":\"tool-call-7\"}]";

    /** Same set as STEPS, rows reordered + keys reshuffled by the producer. */
    private static final String SAME_SET_OTHER_ORDER =
            "[{\"title\":\"收集需求\",\"state\":\"done\",\"evidence\":\"tool-call-7\"},"
                    + "{\"title\":\"生成用例\",\"state\":\"pending\",\"evidence\":\"\"}]";

    /** Stored ledger with one state actually changed. */
    private static final String CHANGED_SET =
            "[{\"title\":\"收集需求\",\"state\":\"done\",\"evidence\":\"tool-call-7\"},"
                    + "{\"title\":\"生成用例\",\"state\":\"skipped\",\"evidence\":\"user-asked\"}]";

    private void stubStored(String stored) {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn(stored == null ? List.of() : List.of(stored));
    }

    @Test
    void firstLedgerForSessionInsertsCanonicalSteps() {
        stubStored(null);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        assertTrue(underTest.upsertIfChanged(9L, 1, STEPS));

        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(contains("INSERT INTO tp_ai_chat_goal_plan"), params.capture());
        MapSqlParameterSource bound = params.getValue();
        assertEquals(GoalPlanLedger.normalize(STEPS), bound.getValue("steps"));
        assertEquals(9L, (Long) bound.getValue("sessionId"));
        assertEquals(1, (Integer) bound.getValue("planVersion"));
    }

    @Test
    void sameSetReplayWritesNothing() {
        stubStored(SAME_SET_OTHER_ORDER);

        assertFalse(underTest.upsertIfChanged(9L, 1, STEPS));

        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void storedUnverifiedNoteSurvivesReplaysAndRealChanges() {
        stubStored("[{\"title\":\"A\",\"state\":\"pending\",\"evidence\":\"\","
                + "\"note\":\"未验证：收口回答无可复核锚\"}]");

        assertFalse(underTest.upsertIfChanged(9L, 1,
                "[{\"title\":\"A\",\"state\":\"pending\",\"evidence\":\"\"}]"),
                "剥离标注的重放被识别为零变化——账上原标注保留（禁删）");
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));

        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        assertTrue(underTest.upsertIfChanged(9L, 1,
                "[{\"title\":\"A\",\"state\":\"done\",\"evidence\":\"tool-call-9\"}]"),
                "真实变化照常落账");
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(contains("UPDATE tp_ai_chat_goal_plan"), params.capture());
        assertTrue(String.valueOf(params.getValue().getValue("steps")).contains("未验证"),
                "随真实变化写入的行必须续带既有标注");
    }

    @Test
    void realChangeUpdatesStepsAndBumpsRevision() {
        stubStored(CHANGED_SET);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        assertTrue(underTest.upsertIfChanged(9L, 1, STEPS));

        verify(jdbc).update(contains("UPDATE tp_ai_chat_goal_plan"),
                any(MapSqlParameterSource.class));
        verify(jdbc).update(contains("revision = revision + 1"),
                any(MapSqlParameterSource.class));
        verify(jdbc, never()).update(contains("INSERT INTO"),
                any(MapSqlParameterSource.class));
    }

    @Test
    void unparsableStepsNeverTouchDatabase() {
        assertFalse(underTest.upsertIfChanged(9L, 1, "not-a-json"));
        assertFalse(underTest.upsertIfChanged(9L, 1, null));

        verifyNoInteractions(jdbc);
    }

    @Test
    void fakeDoneIsDemotedBeforePersisting() {
        stubStored(null);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        String fake = "[{\"title\":\"收集需求\",\"state\":\"done\",\"evidence\":\"\"}]";

        underTest.upsertIfChanged(9L, 1, fake);

        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        String stored = (String) params.getValue().getValue("steps");
        assertFalse(stored.contains("\"done\""), "锚缺失的 done 必须先降级再落库");
        assertTrue(stored.contains("\"pending\""));
    }

    @Test
    void readReturnsStoredJsonOrNothing() {
        stubStored(SAME_SET_OTHER_ORDER);

        assertEquals(SAME_SET_OTHER_ORDER, underTest.findStepsJson(9L, 1));
    }

    @Test
    void databaseDoorDownReadsAsNoLedger() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), eq(String.class)))
                .thenThrow(new RuntimeException("connection refused"));

        assertNull(underTest.findStepsJson(9L, 1));
    }
}
