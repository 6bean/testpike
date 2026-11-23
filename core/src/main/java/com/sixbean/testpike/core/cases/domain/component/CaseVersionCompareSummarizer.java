package com.sixbean.testpike.core.cases.domain.component;

import com.sixbean.testpike.core.cases.api.dto.CaseSnapshotStep;
import com.sixbean.testpike.core.cases.api.dto.CaseVersionCompareInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseVersionDiff;
import com.sixbean.testpike.core.cases.api.dto.CaseVersionFieldChange;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Field changes → one human line for the dialog compare card (AM batch
 * . Pure function over the CaseVersionDiff the shared
 * CaseService.diffVersion door already computed — this class adds NO second
 * diff semantics (it never re-compares field values beyond what the
 * calculator emitted; the step alignment is presentation of the two step
 * lists the calculator carried, keyed by stepNo as the version-history view
 * does).
 * <p>Two honesty rules: an identical pair says so plainly, and the line
 * never contains before/after VALUES — only field labels and step counts, so
 * the card cannot degrade into a JSON dump.
 */
public final class CaseVersionCompareSummarizer {

    private CaseVersionCompareSummarizer() {
    }

    public static CaseVersionCompareInfo summarize(CaseVersionDiff diff) {
        List<CaseVersionFieldChange> changes = diff.changes();
        StringBuilder line = new StringBuilder();
        if (changes.isEmpty()) {
            line.append("用例 #").append(diff.caseId())
                    .append(" 的 v").append(diff.fromVersion())
                    .append(" 与 v").append(diff.toVersion())
                    .append(" 内容完全一致");
        } else {
            line.append("用例 #").append(diff.caseId())
                    .append(" v").append(diff.fromVersion())
                    .append(" → v").append(diff.toVersion())
                    .append("：").append(changes.size()).append(" 处差异：");
            for (int i = 0; i < changes.size(); i++) {
                if (i > 0) {
                    line.append("、");
                }
                CaseVersionFieldChange change = changes.get(i);
                line.append("steps".equals(change.field()) ? stepsPart(change) : change.label());
            }
        }
        if (diff.truncated()) {
            int span = Math.abs(diff.toVersion() - diff.fromVersion());
            line.append("（两版相隔 ").append(span).append(" 版，建议对比相邻版本）");
        }
        return new CaseVersionCompareInfo(diff.caseId(), diff.fromVersion(), diff.toVersion(),
                diff.truncated(), changes.size(), line.toString());
    }

    /** Steps family rendered as added/removed/modified counts aligned by stepNo. */
    private static String stepsPart(CaseVersionFieldChange change) {
        Map<Integer, CaseSnapshotStep> from = indexByStepNo(change.before());
        Map<Integer, CaseSnapshotStep> to = indexByStepNo(change.after());
        int added = 0;
        int modified = 0;
        for (Map.Entry<Integer, CaseSnapshotStep> entry : to.entrySet()) {
            CaseSnapshotStep base = from.get(entry.getKey());
            if (base == null) {
                added++;
            } else if (!base.equals(entry.getValue())) {
                modified++;
            }
        }
        int removed = 0;
        for (Integer stepNo : from.keySet()) {
            if (!to.containsKey(stepNo)) {
                removed++;
            }
        }
        return change.label() + "（改" + modified + "·增" + added + "·删" + removed + "）";
    }

    private static Map<Integer, CaseSnapshotStep> indexByStepNo(Object value) {
        Map<Integer, CaseSnapshotStep> byStepNo = new LinkedHashMap<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof CaseSnapshotStep step) {
                    byStepNo.put(step.stepNo(), step);
                }
            }
        }
        return byStepNo;
    }
}
