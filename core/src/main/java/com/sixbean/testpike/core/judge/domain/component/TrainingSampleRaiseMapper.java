package com.sixbean.testpike.core.judge.domain.component;

import com.sixbean.testpike.core.judge.api.dto.SampleInput;
import com.sixbean.testpike.core.sample.api.dto.TrainingSampleItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <ul>
 * </ul>
 */
@Component
public class TrainingSampleRaiseMapper {

    
    public static final String TYPE_REJECT = "驳回";
    public static final String TYPE_DIFF = "修改差异";

    private final JudgeJsonCodec codec;

    public TrainingSampleRaiseMapper(JudgeJsonCodec codec) {
        this.codec = codec;
    }

    
    public SampleInput toSample(TrainingSampleItem sample, TrainingSampleItem pairedDiff) {
        Map<String, Object> payload = codec.readObjectMap(sample.sampleJson());
        if (TYPE_DIFF.equals(sample.sampleType())) {
            return new SampleInput(diffInput(sample, payload), humanizeChanges(payload),
                    List.of(), "training-raise", 0);
        }
        Map<String, Object> caseSnapshot = asMap(payload.get("case"));
        List<String> refs = new ArrayList<>();
        refs.add(codec.write(Map.of("sourceSampleId", sample.id(),
                "caseSnapshot", caseSnapshot.isEmpty() ? payload : caseSnapshot)));
        String expected = pairedDiff == null ? null
                : humanizeChanges(codec.readObjectMap(pairedDiff.sampleJson()));
        return new SampleInput(rejectInput(sample, caseSnapshot, asMap(payload.get("review"))),
                expected, refs, "training-raise", 0);
    }

    private String rejectInput(TrainingSampleItem sample, Map<String, Object> caseMap,
                               Map<String, Object> reviewMap) {
        StringBuilder buf = new StringBuilder();
        buf.append("【被评用例】").append(textOr(caseMap.get("caseNo"), "用例#" + sample.caseId()))
                .append(' ').append(textOr(caseMap.get("title"), ""))
                .append('\n');
        appendIfPresent(buf, "【前置条件】", caseMap.get("precondition"));
        appendIfPresent(buf, "【预期结果】", caseMap.get("expectedResult"));
        appendIfPresent(buf, "【步骤】", stepsText(caseMap.get("steps")));
        String remark = textOr(reviewMap.get("remark"), "");
        if (!remark.isBlank()) {
            buf.append("【评审驳回意见】").append(remark);
            String types = feedbackTypes(reviewMap.get("feedbackTypes"));
            if (!types.isBlank()) {
                buf.append("（问题类型：").append(types).append('）');
            }
            buf.append('\n');
        }
        return buf.toString().stripTrailing();
    }

    private String diffInput(TrainingSampleItem sample, Map<String, Object> payload) {
        Object from = payload.get("fromVersion");
        Object to = payload.get("toVersion");
        return "【用例修改差异】用例 " + payload.getOrDefault("caseId", sample.caseId())
                + "（版本 " + (from == null ? "-" : from) + " → " + (to == null ? "-" : to) + "）"
                + "——改进后的用例即期望产出方向，来源训练样本 " + sample.id();
    }

    
    private String humanizeChanges(Map<String, Object> payload) {
        Object raw = payload.get("changes");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        StringBuilder buf = new StringBuilder();
        for (Object item : list) {
            Map<String, Object> change = asMap(item);
            if (change.isEmpty()) {
                continue;
            }
            buf.append(textOr(change.get("label"), textOr(change.get("field"), "字段")))
                    .append('：').append(textOr(change.get("before"), "（空）"))
                    .append(" → ").append(textOr(change.get("after"), "（空）"))
                    .append('\n');
        }
        String text = buf.toString().stripTrailing();
        return text.isEmpty() ? null : text;
    }

    private String stepsText(Object steps) {
        if (!(steps instanceof List<?> list)) {
            return "";
        }
        StringBuilder buf = new StringBuilder();
        for (Object item : list) {
            Map<String, Object> step = asMap(item);
            buf.append(step.getOrDefault("stepNo", "?")).append(". ")
                    .append(textOr(step.get("description"), "")).append("; ");
        }
        return buf.toString().strip();
    }

    private String feedbackTypes(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return "";
        }
        return list.stream().map(String::valueOf).reduce((a, b) -> a + "、" + b).orElse("");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private void appendIfPresent(StringBuilder buf, String label, Object value) {
        String text = textOr(value, "");
        if (!text.isBlank()) {
            buf.append(label).append(text).append('\n');
        }
    }

    private String textOr(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
