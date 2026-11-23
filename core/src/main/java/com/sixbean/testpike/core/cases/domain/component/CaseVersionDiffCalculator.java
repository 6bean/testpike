package com.sixbean.testpike.core.cases.domain.component;

import com.sixbean.testpike.core.cases.api.dto.CaseSnapshot;
import com.sixbean.testpike.core.cases.api.dto.CaseSnapshotStep;
import com.sixbean.testpike.core.cases.api.dto.CaseVersionFieldChange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Version field-level diff calculation component (contract: diff returns field-level before/after).
 * <p>Comparison fields include user-editable content fields, status (review snapshot diff must include status changes), and script.
 * assigneeId (system-assigned data) is excluded; before is the baseline version (from) value, and after is the target version (to) value.
 * <p>Pure function (no IO), directly instantiable for L2 tests as the data source for step addition, deletion, and update highlighting.
 *
 */
public final class CaseVersionDiffCalculator {

    /** Chinese display names for fields (directly displayed in frontend columns/labels, following the plain-language rule for alert messages). */
    private static final LinkedHashMap<String, String> FIELD_LABELS = new LinkedHashMap<>();

    static {
        FIELD_LABELS.put("title", "测试目标");
        FIELD_LABELS.put("precondition", "前置条件");
        FIELD_LABELS.put("expectedResult", "预期结果");
        FIELD_LABELS.put("priority", "优先级");
        FIELD_LABELS.put("confidence", "置信度");
        FIELD_LABELS.put("endType", "端属性");
        FIELD_LABELS.put("phase", "阶段");
        FIELD_LABELS.put("status", "评审状态");
        FIELD_LABELS.put("steps", "操作步骤");
        FIELD_LABELS.put("script", "脚本");
    }

    private CaseVersionDiffCalculator() {
    }

    /** Field-level differences between two snapshots (returns only unequal fields; order follows the FIELD_LABELS definition order). */
    public static List<CaseVersionFieldChange> diff(CaseSnapshot from, CaseSnapshot to) {
        List<CaseVersionFieldChange> changes = new ArrayList<>();
        compare(changes, "title", from.title(), to.title());
        compare(changes, "precondition", from.precondition(), to.precondition());
        compare(changes, "expectedResult", from.expectedResult(), to.expectedResult());
        compare(changes, "priority", from.priority(), to.priority());
        compare(changes, "confidence", from.confidence(), to.confidence());
        compare(changes, "endType", from.endType(), to.endType());
        compare(changes, "phase", from.phase(), to.phase());
        compare(changes, "status", from.status(), to.status());
        compareSteps(changes, from.steps(), to.steps());
        compare(changes, "script", from.script(), to.script());
        return changes;
    }

 /** Number of changed fields relative to the baseline (the list's "changed field count" column). */
    public static int changedFieldCount(CaseSnapshot from, CaseSnapshot to) {
        return diff(from, to).size();
    }

    /** Step list diff (individual changes and quantity add/remove changes are merged into a single steps change, with the value set to the step snapshot for frontend item-by-item highlighting). */
    private static void compareSteps(List<CaseVersionFieldChange> changes,
                                     List<CaseSnapshotStep> fromSteps, List<CaseSnapshotStep> toSteps) {
        List<CaseSnapshotStep> from = fromSteps == null ? List.of() : fromSteps;
        List<CaseSnapshotStep> to = toSteps == null ? List.of() : toSteps;
        if (!from.equals(to)) {
            changes.add(new CaseVersionFieldChange("steps", FIELD_LABELS.get("steps"), from, to));
        }
    }

    private static void compare(List<CaseVersionFieldChange> changes, String field,
                                Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.add(new CaseVersionFieldChange(field, FIELD_LABELS.get(field), before, after));
        }
    }
}
