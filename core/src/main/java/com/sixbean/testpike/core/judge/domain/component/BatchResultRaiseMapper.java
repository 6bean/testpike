package com.sixbean.testpike.core.judge.domain.component;

import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.common.defect.DefectLabels;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.judge.api.dto.SampleInput;
import com.sixbean.testpike.core.requirement.domain.component.TraceLabels;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * <ul>
 * </ul>
 */
@Component
public class BatchResultRaiseMapper {

    private final JudgeJsonCodec codec;

    public BatchResultRaiseMapper(JudgeJsonCodec codec) {
        this.codec = codec;
    }

    
    public SampleInput toSample(ExecTaskInfo task, CaseInfo caseInfo) {
        StringBuilder buf = new StringBuilder();
        buf.append("【被评用例】").append(nvl(caseInfo.caseNo(), "用例#" + task.caseId()))
                .append(' ').append(nvl(caseInfo.title(), "")).append('\n');
        appendIfPresent(buf, "【前置条件】", caseInfo.precondition());
        appendIfPresent(buf, "【预期结果】", caseInfo.expectedResult());
        buf.append("【执行结果】").append(TraceLabels.execStatus(task.status()));
        if (task.errorType() != null) {
            buf.append("（分类：").append(DefectLabels.errorTypeLabel(task.errorType())).append('）');
        }
        buf.append('\n');
        appendIfPresent(buf, "【失败详情】", task.errorDetail());
        appendIfPresent(buf, "【AI 归因】", task.aiFailureNote());
        String ref = codec.write(Map.of(
                "sourceTaskId", task.id(),
                "caseId", task.caseId(),
                "finishedAt", nvl(task.finishedAt(), ""),
                "actionJson", nvl(task.actionJson(), "")));
        return new SampleInput(buf.toString().stripTrailing(), null, List.of(ref),
                "batch-export", 0);
    }

    private void appendIfPresent(StringBuilder buf, String label, String value) {
        if (value != null && !value.isBlank()) {
            buf.append(label).append(value).append('\n');
        }
    }

    private String nvl(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
