package com.sixbean.testpike.core.judge.domain.component;

import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInfo;
import com.sixbean.testpike.core.judge.api.dto.SampleInput;
import com.sixbean.testpike.core.requirement.api.dto.RequirementInfo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adopted excellent AI case -&gt; golden sample mapping (, BA ruling item 8,
 * sample+judge base — it never re-implements versioning or snapshot digests,
 * the caller feeds the result into the single-source publish path).
 * <p>Pure function, zero queries — case rows and their linked requirements are
 * fetched by the caller and passed in. Mapping shape (no-fabrication rule):
 * <ul>
 * <li>input = the generation task context: the linked requirement titles plus
 * their acceptance criteria — the same text surface a generation run is driven
 * from, so the replay compares template behaviour on a frozen input;</li>
 * <li>expected = the human-approved excellent case (title/precondition/
 * expected result/ordered steps) — the reference answer this pack pins;</li>
 * <li>reference_docs = traceability anchor JSON (caseId/caseNo/projectId/
 * requirementIds), never a second copy of the answer.</li>
 * </ul>
 */
@Component
public class AdoptedCaseRaiseMapper {

    /** Single-source label: the four accepted judge sample sources (J6 ②). */
    static final String SOURCE_LABEL = "batch-export";

    private final JudgeJsonCodec codec;

    public AdoptedCaseRaiseMapper(JudgeJsonCodec codec) {
        this.codec = codec;
    }

    /**
     * Maps one adopted excellent AI case with its linked requirements into a
     * golden sample input. Eligibility gates (source/excellent/status) are the
     * caller's job — this piece only renders the frozen pair.
     */
    public SampleInput toSample(CaseInfo caseInfo, List<RequirementInfo> requirements) {
        StringBuilder input = new StringBuilder("【生成需求上下文】\n");
        List<Long> requirementIds = new ArrayList<>();
        for (RequirementInfo req : requirements) {
            input.append("- ").append(nvl(req.title(), "需求#" + req.id()));
            if (req.acceptanceCriteria() != null && !req.acceptanceCriteria().isBlank()) {
                input.append("｜验收标准：").append(req.acceptanceCriteria().strip());
            }
            input.append('\n');
            requirementIds.add(req.id());
        }
        StringBuilder expected = new StringBuilder();
        expected.append("【用例编号】").append(nvl(caseInfo.caseNo(), "用例#" + caseInfo.id())).append('\n');
        expected.append("【标题】").append(nvl(caseInfo.title(), "")).append('\n');
        if (caseInfo.precondition() != null && !caseInfo.precondition().isBlank()) {
            expected.append("【前置条件】").append(caseInfo.precondition().strip()).append('\n');
        }
        if (caseInfo.expectedResult() != null && !caseInfo.expectedResult().isBlank()) {
            expected.append("【预期结果】").append(caseInfo.expectedResult().strip()).append('\n');
        }
        List<CaseStepInfo> steps = caseInfo.steps() == null ? List.of() : caseInfo.steps();
        if (!steps.isEmpty()) {
            expected.append("【步骤】\n");
            steps.forEach(step -> expected.append(step.stepNo()).append(". ")
                    .append(nvl(step.description(), ""))
                    .append(step.isAssert() == 1 ? "（断言）" : "").append('\n'));
        }
        Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("sourceCaseId", caseInfo.id());
        anchor.put("caseNo", nvl(caseInfo.caseNo(), ""));
        anchor.put("projectId", caseInfo.projectId());
        anchor.put("requirementIds", requirementIds);
        return new SampleInput(input.toString().stripTrailing(), expected.toString().stripTrailing(),
                List.of(codec.write(anchor)), SOURCE_LABEL, 0);
    }

    private String nvl(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
