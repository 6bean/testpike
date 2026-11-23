package com.sixbean.testpike.core.aichat.domain.component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.cases.api.dto.CaseSnapshot;
import com.sixbean.testpike.core.cases.api.dto.CaseSnapshotStep;
import com.sixbean.testpike.core.cases.domain.component.CaseSnapshotCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Three-way revision-merge check for dialog draft proposals that land on a
 * stale optimistic anchor : instead of refusing the whole write the
 * moment the row version moved on, compare what the PROPOSAL touches against
 * what CONCURRENT EDITORS touched between the draft's base version and the
 * current version. Disjoint changes rebase onto the latest version and both
 * writes survive; intersecting changes are refused with every touched item
 * named; an unreconstructable baseline is refused conservatively.
 * <p>Baseline source is the immutable version snapshot table only
 * (tp_case_version, exact row per version): the live row is never used as a
 * stand-in base — a missing snapshot means BASELINE_MISSING, never a guessed
 * "nothing changed" (recon pitfall registered in PROGRESS ).
 * <p>Field-alignment rules (versioning design ruling 11): same field =
 * intersect; steps align by stepNo and are judged step by step; a counterpart
 * step insertion/deletion (snapshot size drift) is a structural change judged
 * conservatively as intersecting whenever the proposal touches steps.
 * <p>Single-sourced labels: field keys here double as the proposal payload
 * keys used by the draft strategies, so the mapping stays one place.
 */
@Component
public class RevisionMergeGate {

    /** Proposal field key -> on-screen Chinese item name for refusal sentences. */
    private static final Map<String, String> FIELD_LABELS = Map.of(
            "title", "标题",
            "precondition", "前置条件",
            "expectedResult", "预期结果",
            "priority", "优先级",
            "confidence", "置信度",
            "endType", "端类型",
            "phase", "阶段",
            "acceptanceCriteria", "验收标准",
            "chapter", "章节");

    /**
     * Requirement editable fields in fixed display order (title first): the
     * tp_requirement_version snapshot carries these keys verbatim, so refusal
     * items name them in a deterministic order sharing the label table above.
     */
    private static final List<String> REQUIREMENT_KEYS =
            List.of("title", "acceptanceCriteria", "chapter");

    private static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    public RevisionMergeGate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Assess one stale-anchor proposal against the snapshot pair
     * (baseVersion, currentVersion) read exactly from tp_case_version.
     *
     * @param proposalFields keys the proposal payload actually carries
     *                       (title/precondition/... same spelling as the payload)
     * @param proposalSteps  normalized steps when the proposal touches the step
     *                       region, null when steps stay untouched
     */
    public RevisionMergeOutcome assess(long caseId, int baseVersion, int currentVersion,
                                       Set<String> proposalFields,
                                       List<CaseSnapshotStep> proposalSteps) {
        CaseSnapshot base = snapshotExact(caseId, baseVersion);
        CaseSnapshot latest = snapshotExact(caseId, currentVersion);
        return compare(base, latest, currentVersion, proposalFields, proposalSteps);
    }

    /**
 * Requirement counterpart of {@link #assess}: same ruling-11 field rules
 * over the tp_requirement_version snapshot pair .
 * Requirements have no step region, so the outcome never carries mergedSteps.
 */
    public RevisionMergeOutcome assessRequirement(long requirementId, int baseVersion,
                                                  int currentVersion, Set<String> proposalFields) {
        Map<String, Object> base = requirementSnapshotExact(requirementId, baseVersion);
        Map<String, Object> latest = requirementSnapshotExact(requirementId, currentVersion);
        return compareRequirementFields(base, latest, currentVersion, proposalFields);
    }

    /**
     * Pure requirement-field three-way comparison — the intersection rule is
     * the SAME intersect() single source the case path uses (R40): a field the
     * proposal carries and the counterpart moved is an intersection.
     */
    static RevisionMergeOutcome compareRequirementFields(Map<String, Object> base,
                                                         Map<String, Object> latest,
                                                         int currentVersion,
                                                         Set<String> proposalFields) {
        if (base == null || latest == null) {
            return new RevisionMergeOutcome(RevisionMergeStatus.BASELINE_MISSING, null, List.of(), null);
        }
        List<String> items = new ArrayList<>();
        for (String key : REQUIREMENT_KEYS) {
            intersect(items, proposalFields, key, base.get(key), latest.get(key));
        }
        if (!items.isEmpty()) {
            return new RevisionMergeOutcome(RevisionMergeStatus.CONFLICT, null, items, null);
        }
        return new RevisionMergeOutcome(RevisionMergeStatus.REBASE, currentVersion, List.of(), null);
    }

    /**
     * Pure three-way comparison — split out so the differential spectrum is
     * testable without a database (L2 isolates infrastructure only).
     */
    static RevisionMergeOutcome compare(CaseSnapshot base, CaseSnapshot latest,
                                        int currentVersion, Set<String> proposalFields,
                                        List<CaseSnapshotStep> proposalSteps) {
        if (base == null || latest == null) {
            return new RevisionMergeOutcome(RevisionMergeStatus.BASELINE_MISSING, null, List.of(), null);
        }
        List<String> items = new ArrayList<>();
        intersect(items, proposalFields, "title", base.title(), latest.title());
        intersect(items, proposalFields, "precondition", base.precondition(), latest.precondition());
        intersect(items, proposalFields, "expectedResult", base.expectedResult(), latest.expectedResult());
        intersect(items, proposalFields, "priority", base.priority(), latest.priority());
        intersect(items, proposalFields, "confidence", base.confidence(), latest.confidence());
        intersect(items, proposalFields, "endType", base.endType(), latest.endType());
        intersect(items, proposalFields, "phase", base.phase(), latest.phase());

        List<CaseSnapshotStep> mergedSteps = null;
        if (proposalSteps != null) {
            List<CaseSnapshotStep> baseSteps = base.steps() == null ? List.of() : base.steps();
            List<CaseSnapshotStep> latestSteps = latest.steps() == null ? List.of() : latest.steps();
            if (baseSteps.size() != latestSteps.size()) {
                // Counterpart inserted or deleted a step: stepNo alignment is gone,
                // structural change is judged conservatively as intersecting (ruling 11).
                items.add("步骤");
            } else {
                Map<Integer, CaseSnapshotStep> baseByNo = index(baseSteps);
                Map<Integer, CaseSnapshotStep> latestByNo = index(latestSteps);
                mergedSteps = new ArrayList<>();
                for (CaseSnapshotStep p : proposalSteps) {
                    CaseSnapshotStep b = baseByNo.get(p.stepNo());
                    CaseSnapshotStep l = latestByNo.get(p.stepNo());
                    boolean proposalKeepsBase = Objects.equals(p, b);
                    boolean counterpartMoved = l != null && !Objects.equals(l, b);
                    if (!proposalKeepsBase && counterpartMoved) {
                        items.add("步骤 " + p.stepNo());
                    }
                    // Overlay: the proposal only rewrites what it actually moved;
                    // a step it left at base value takes the counterpart's newer content,
                    // so a rebased write can never roll back a concurrent step edit.
                    mergedSteps.add(proposalKeepsBase && counterpartMoved ? l : p);
                }
            }
        }
        if (!items.isEmpty()) {
            return new RevisionMergeOutcome(RevisionMergeStatus.CONFLICT, null, items, null);
        }
        return new RevisionMergeOutcome(RevisionMergeStatus.REBASE, currentVersion, List.of(), mergedSteps);
    }

    /** Same field touched by the proposal and moved by the counterpart = intersection. */
    private static void intersect(List<String> items, Set<String> proposalFields,
                                  String key, Object baseValue, Object latestValue) {
        if (proposalFields.contains(key) && !Objects.equals(baseValue, latestValue)) {
            String label = FIELD_LABELS.get(key);
            if (!items.contains(label)) {
                items.add(label);
            }
        }
    }

    private static Map<Integer, CaseSnapshotStep> index(List<CaseSnapshotStep> steps) {
        Map<Integer, CaseSnapshotStep> out = new LinkedHashMap<>();
        for (CaseSnapshotStep step : steps) {
            out.put(step.stepNo(), step);
        }
        return out;
    }

    /** Exact-version snapshot row (uk case_id+version); absent/corrupt => null, never re-read the live row. */
    private CaseSnapshot snapshotExact(long caseId, int version) {
        List<String> jsons = jdbcTemplate.queryForList(
                "SELECT snapshot_json::text FROM tp_case_version WHERE case_id = ? AND version = ?",
                String.class, caseId, version);
        if (jsons.isEmpty()) {
            return null;
        }
        try {
            return CaseSnapshotCodec.fromJson(jsons.get(0));
        } catch (Exception e) {
            return null; // Corrupt snapshot: undetermined baseline => conservative refusal upstream.
        }
    }

    /**
     * Exact-version requirement snapshot row (uk requirement_id+version),
     * decoded to its raw field map — the same map shape the write path stores.
     * Absent/corrupt => null: never guess a baseline from the live row.
     */
    private Map<String, Object> requirementSnapshotExact(long requirementId, int version) {
        List<String> jsons = jdbcTemplate.queryForList(
                "SELECT snapshot_json::text FROM tp_requirement_version "
                        + "WHERE requirement_id = ? AND version = ?",
                String.class, requirementId, version);
        if (jsons.isEmpty()) {
            return null;
        }
        try {
            return SNAPSHOT_MAPPER.readValue(jsons.get(0),
                    new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return null; // Corrupt snapshot: undetermined baseline => conservative refusal upstream.
        }
    }
}
