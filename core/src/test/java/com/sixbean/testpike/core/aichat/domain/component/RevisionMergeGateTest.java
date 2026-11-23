package com.sixbean.testpike.core.aichat.domain.component;

import com.sixbean.testpike.core.cases.api.dto.CaseSnapshot;
import com.sixbean.testpike.core.cases.api.dto.CaseSnapshotStep;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * differential spectrum for the dialog-draft revision-merge gate:
 * disjoint overlay rebases, same-field intersection names every item,
 * steps align by stepNo, counterpart step insert/delete is a conservative
 * structural intersection, missing baseline refuses without guessing.
 * Pure-logic layer (compare); the snapshot rows are built in memory —
 * the DB read path stays on the shared codec single source.
 */
class RevisionMergeGateTest {

    private static CaseSnapshot snap(String title, String precondition,
                                     List<CaseSnapshotStep> steps) {
        return new CaseSnapshot(title, precondition, "exp", 3, 80, "web", 1,
                1, null, steps, "v2", null);
    }

    @Test
    void disjointOverlayRebasesOntoLatestVersion() {
        // Draft touches title; the concurrent editor moved only precondition.
        CaseSnapshot base = snap("旧标题", "旧前置", List.of(new CaseSnapshotStep(1, "步骤一", 0)));
        CaseSnapshot latest = snap("旧标题", "他人改的前置", List.of(new CaseSnapshotStep(1, "步骤一", 0)));

        RevisionMergeOutcome outcome = RevisionMergeGate.compare(
                base, latest, 8, Set.of("title"), null);

        assertEquals(RevisionMergeStatus.REBASE, outcome.status());
        assertEquals(8, outcome.targetVersion());
        assertTrue(outcome.conflictItems().isEmpty());
    }

    @Test
    void sameFieldIntersectionNamesTheField() {
        CaseSnapshot base = snap("旧标题", "旧前置", List.of());
        CaseSnapshot latest = snap("他人改的标题", "旧前置", List.of());

        RevisionMergeOutcome outcome = RevisionMergeGate.compare(
                base, latest, 8, Set.of("title", "precondition"), null);

        assertEquals(RevisionMergeStatus.CONFLICT, outcome.status());
        assertEquals(List.of("标题"), outcome.conflictItems());
        assertNull(outcome.targetVersion());
    }

    @Test
    void untouchedCounterpartStepKeepsRebase() {
        // Proposal rewrites step 2; the concurrent editor changed only step 3.
        CaseSnapshot base = snap("t", "p", List.of(
                new CaseSnapshotStep(1, "步骤一", 0),
                new CaseSnapshotStep(2, "旧的第二步", 0),
                new CaseSnapshotStep(3, "步骤三", 1)));
        CaseSnapshot latest = snap("t", "p", List.of(
                new CaseSnapshotStep(1, "步骤一", 0),
                new CaseSnapshotStep(2, "旧的第二步", 0),
                new CaseSnapshotStep(3, "他人改的第三步", 1)));

        RevisionMergeOutcome outcome = RevisionMergeGate.compare(
                base, latest, 9, Set.of("steps"),
                List.of(new CaseSnapshotStep(1, "步骤一", 0),
                        new CaseSnapshotStep(2, "提案的新第二步", 0),
                        new CaseSnapshotStep(3, "步骤三", 1)));

        assertEquals(RevisionMergeStatus.REBASE, outcome.status());
        assertEquals(9, outcome.targetVersion());
        // Overlay merge: the step the proposal left at base value keeps the
        // counterpart's newer content — a rebased write never rolls it back.
        assertEquals(List.of(new CaseSnapshotStep(1, "步骤一", 0),
                new CaseSnapshotStep(2, "提案的新第二步", 0),
                new CaseSnapshotStep(3, "他人改的第三步", 1)), outcome.mergedSteps());
    }

    @Test
    void sameStepNoIntersectionNamesTheStep() {
        // Both the draft and a concurrent editor moved step 2.
        CaseSnapshot base = snap("t", "p", List.of(
                new CaseSnapshotStep(1, "步骤一", 0),
                new CaseSnapshotStep(2, "旧的第二步", 0)));
        CaseSnapshot latest = snap("t", "p", List.of(
                new CaseSnapshotStep(1, "步骤一", 0),
                new CaseSnapshotStep(2, "他人改的第二步", 0)));

        RevisionMergeOutcome outcome = RevisionMergeGate.compare(
                base, latest, 7, Set.of("steps"),
                List.of(new CaseSnapshotStep(1, "步骤一", 0),
                        new CaseSnapshotStep(2, "提案的新第二步", 0)));

        assertEquals(RevisionMergeStatus.CONFLICT, outcome.status());
        assertEquals(List.of("步骤 2"), outcome.conflictItems());
    }

    @Test
    void counterpartStructuralStepChangeIsConservativeIntersection() {
        // Concurrent editor inserted a step (2 -> 3): alignment lost, refuse.
        CaseSnapshot base = snap("t", "p", List.of(
                new CaseSnapshotStep(1, "步骤一", 0),
                new CaseSnapshotStep(2, "步骤二", 0)));
        CaseSnapshot latest = snap("t", "p", List.of(
                new CaseSnapshotStep(1, "步骤一", 0),
                new CaseSnapshotStep(2, "新插入的步骤", 0),
                new CaseSnapshotStep(3, "步骤二", 0)));

        RevisionMergeOutcome outcome = RevisionMergeGate.compare(
                base, latest, 6, Set.of("steps"),
                List.of(new CaseSnapshotStep(1, "提案改写的第一步", 0),
                        new CaseSnapshotStep(2, "步骤二", 0)));

        assertEquals(RevisionMergeStatus.CONFLICT, outcome.status());
        assertTrue(outcome.conflictItems().contains("步骤"));
    }

    @Test
    void missingBaselineRefusesWithoutGuessing() {
        CaseSnapshot latest = snap("t", "p", List.of());

        RevisionMergeOutcome noBase = RevisionMergeGate.compare(
                null, latest, 4, Set.of("title"), null);
        assertEquals(RevisionMergeStatus.BASELINE_MISSING, noBase.status());

        RevisionMergeOutcome noLatest = RevisionMergeGate.compare(
                latest, null, 4, Set.of("title"), null);
        assertEquals(RevisionMergeStatus.BASELINE_MISSING, noLatest.status());
    }

    // ---- requirement surface ----

    private static java.util.Map<String, Object> reqSnap(String title, String criteria, String chapter) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("title", title);
        m.put("acceptanceCriteria", criteria);
        m.put("chapter", chapter);
        return m;
    }

    @Test
    void requirementDisjointOverlayRebasesOntoLatestVersion() {
        // Draft touches title; the concurrent editor moved only acceptanceCriteria.
        RevisionMergeOutcome outcome = RevisionMergeGate.compareRequirementFields(
                reqSnap("旧标题", "旧验收", "第1章"),
                reqSnap("旧标题", "他人改的验收", "第1章"),
                8, Set.of("title"));

        assertEquals(RevisionMergeStatus.REBASE, outcome.status());
        assertEquals(8, outcome.targetVersion());
        assertTrue(outcome.conflictItems().isEmpty());
        assertNull(outcome.mergedSteps());
    }

    @Test
    void requirementIntersectionNamesEveryMovedTouchedField() {
        RevisionMergeOutcome outcome = RevisionMergeGate.compareRequirementFields(
                reqSnap("旧标题", "旧验收", "第1章"),
                reqSnap("他人改的标题", "旧验收", "他人改的章节"),
                9, Set.of("title", "acceptanceCriteria", "chapter"));

        assertEquals(RevisionMergeStatus.CONFLICT, outcome.status());
        assertEquals(List.of("标题", "章节"), outcome.conflictItems());
    }

    @Test
    void requirementUntouchedCounterpartMoveNeverIntersects() {
        // Counterpart moved title; the draft only carries chapter (unchanged side).
        RevisionMergeOutcome outcome = RevisionMergeGate.compareRequirementFields(
                reqSnap("旧标题", "旧验收", "第1章"),
                reqSnap("他人改的标题", "旧验收", "第1章"),
                6, Set.of("chapter"));

        assertEquals(RevisionMergeStatus.REBASE, outcome.status());
    }

    @Test
    void requirementMissingBaselineRefusesWithoutGuessing() {
        RevisionMergeOutcome noBase = RevisionMergeGate.compareRequirementFields(
                null, reqSnap("t", "c", "ch"), 4, Set.of("title"));
        assertEquals(RevisionMergeStatus.BASELINE_MISSING, noBase.status());

        RevisionMergeOutcome noLatest = RevisionMergeGate.compareRequirementFields(
                reqSnap("t", "c", "ch"), null, 4, Set.of("title"));
        assertEquals(RevisionMergeStatus.BASELINE_MISSING, noLatest.status());
    }

    @Test
    void untouchedFieldsNeverIntersect() {
        // Concurrent editor moved confidence/phase; the draft only carries title.
        CaseSnapshot base = new CaseSnapshot("t", "p", "e", 3, 80, "web", 1,
                1, null, List.of(), "v2", null);
        CaseSnapshot latest = new CaseSnapshot("t", "p", "e", 3, 95, "web", 2,
                1, null, List.of(), "v2", null);

        RevisionMergeOutcome outcome = RevisionMergeGate.compare(
                base, latest, 5, Set.of("title"), null);

        assertEquals(RevisionMergeStatus.REBASE, outcome.status());
        assertEquals(5, outcome.targetVersion());
    }
}
