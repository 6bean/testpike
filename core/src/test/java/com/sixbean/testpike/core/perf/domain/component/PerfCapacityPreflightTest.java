package com.sixbean.testpike.core.perf.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Capacity pre-flight wording spectrum (CF-5, resource reservation self-check).
 * <p>The pre-flight card is read-only UX wording in front of the launch button:
 * it must tell the operator, before pressing "start", how many executors with
 * the run's engine capability exist, how much idle capacity they carry, and
 * what the wait outlook is. Every branch here mirrors one real pool state; the
 * judgements themselves come from the same online/capability sources the
 * dispatch path uses (this test pins only the wording projection of those
 * numbers, never a second decision rule).
 * <p>Fail-closed posture contract: an unresolvable concurrency tier yields no
 * claim (null) — the card stays silent instead of guessing, while the launch
 * gate still rejects malformed pressure config on its own.
 */
class PerfCapacityPreflightTest {

    @Test
    void emptyFleetPromisesQueueAndWaitForFirstNode() {
        String note = PerfCapacityPreflight.noteOf(300, "GATLING", 0, 0, 0, 0);
        assertTrue(note.contains("没有在线的压测执行机"), note);
        assertTrue(note.contains("上线后会自动开始"), note);
    }

    @Test
    void onlineButIncapableNamesEngineAndRemedy() {
        String note = PerfCapacityPreflight.noteOf(300, "K6", 3, 0, 0, 0);
        assertTrue(note.contains("引擎 K6"), note);
        assertTrue(note.contains("3 台中具备该能力的为 0 台"), note);
        assertTrue(note.contains("安装"), note);
    }

    @Test
    void sufficientIdleCapacityWithoutQueueStatesImmediateStart() {
        String note = PerfCapacityPreflight.noteOf(300, "GATLING", 4, 2, 320, 0);
        assertTrue(note.contains("具备 GATLING 能力的执行机 2 台"), note);
        assertTrue(note.contains("空闲容量 320"), note);
        assertTrue(note.contains("本次需要并发 300"), note);
        assertTrue(note.contains("容量充足，可立即开始"), note);
    }

    @Test
    void shortIdleCapacityPromisesWaitForRelease() {
        String note = PerfCapacityPreflight.noteOf(500, "GATLING", 4, 2, 120, 0);
        assertTrue(note.contains("空闲容量不足，任务将等待容量释放后开始"), note);
    }

    @Test
    void pendingQueueAheadIsNamedRegardlessOfOwnCapacity() {
        String note = PerfCapacityPreflight.noteOf(100, "K6", 2, 1, 200, 7);
        assertTrue(note.contains("前方还有 7 个压测任务排队"), note);
        assertTrue(note.contains("按领取顺序开始"), note);
    }

    @Test
    void unresolvableTierYieldsNoClaim() {
        assertNull(PerfCapacityPreflight.noteOf(0, "K6", 2, 1, 200, 0));
        assertNull(PerfCapacityPreflight.noteOf(-5, "K6", 0, 0, 0, 0));
    }
}
