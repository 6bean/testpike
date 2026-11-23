package com.sixbean.testpike.core.cases.domain.component;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseSnapshot;
import com.sixbean.testpike.core.cases.api.dto.CaseSnapshotStep;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;

import java.util.List;

/**
 * Encode and decode use-case version snapshots between snapshot_json and use-case details/scripts using bidirectional Jackson binding.
 * <p>The snapshot includes the script, following the semantics that the script is captured with the version snapshot; from/to conversions are provided for diff and rollback replay.
 * <p>Corruption defense: decoding an invalid structure (scalar, missing steps, or type mismatch) throws an exception.
 * {@link IllegalArgumentException} is thrown, and the caller marks it as corrupted and rejects diff/rollback without silently swallowing it.
 *
 */
public final class CaseSnapshotCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    private CaseSnapshotCodec() {
    }

    /** Use-case details (must include steps) plus current script action JSON/schemaVersion produce a snapshot object, where script=null indicates no script. */
    public static CaseSnapshot of(CaseInfo caseInfo, String scriptSchemaVersion, String scriptJson) {
        List<CaseSnapshotStep> steps = caseInfo.steps() == null ? List.of()
                : caseInfo.steps().stream()
                        .map(s -> new CaseSnapshotStep(s.stepNo(), s.description(), s.isAssert()))
                        .toList();
        return new CaseSnapshot(caseInfo.title(), caseInfo.precondition(), caseInfo.expectedResult(),
                caseInfo.priority(), caseInfo.confidence(), caseInfo.endType(), caseInfo.phase(),
                caseInfo.status(), caseInfo.assigneeId(), steps, scriptSchemaVersion, scriptJson);
    }

    /** Convert the snapshot object to persisted JSON text. */
    public static String toJson(CaseSnapshot snapshot) {
        try {
            return MAPPER.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("版本快照序列化失败", e);
        }
    }

    /**
     * Convert persisted JSON text to a snapshot object; invalid structures (scalar, array, or missing steps) throw an exception.
 * IllegalArgumentException is the corruption detection point.
     */
    public static CaseSnapshot fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("快照 JSON 为空");
        }
        try {
            CaseSnapshot snapshot = MAPPER.readValue(json, CaseSnapshot.class);
            if (snapshot == null || snapshot.steps() == null) {
                throw new IllegalArgumentException("快照结构不完整（steps 缺失）");
            }
            return snapshot;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("快照 JSON 损坏：无法反序列化为版本快照", e);
        }
    }

    /** Convert snapshot steps into update input steps for rollback replay, where non-null steps means whole-replacement semantics. */
    public static List<CaseStepInput> toStepInputs(CaseSnapshot snapshot) {
        return snapshot.steps().stream()
                .map(s -> new CaseStepInput(s.stepNo(), s.description(), s.isAssert()))
                .toList();
    }
}
