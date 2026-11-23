package com.sixbean.testpike.core.script.domain.component;

import com.sixbean.testpike.core.cases.api.dto.CaseVersionFieldChange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ActionJsonDiffCalculatorTest {

    private static String json(String title, String stepsJson, String envJson) {
        return "{\"version\":\"v2\",\"caseId\":\"TC-B5\",\"title\":\"" + title + "\","
                + "\"endType\":\"web\",\"setup\":[{\"action\":\"goto\",\"target\":{\"url\":\"/login\"}}],"
                + "\"steps\":[" + stepsJson + "],\"env\":" + envJson + "}";
    }

    private static final String STEP1 = "{\"action\":\"input\",\"target\":{\"css\":\"#u\"},\"value\":\"a\"}";
    private static final String STEP2 = "{\"action\":\"expect\",\"target\":{\"text\":\"ok\"}}";

    private static CaseVersionFieldChange find(List<CaseVersionFieldChange> changes, String field) {
        return changes.stream().filter(c -> field.equals(c.field())).findFirst()
                .orElseThrow(() -> new AssertionError("缺少字段变更: " + field + " (实际=" + changes + ")"));
    }

    @Test
    void 顶层字段变更_仅返回不等字段() {
        String from = json("登录A", STEP1, "{\"ENV\":\"dev\"}");
        String to = json("登录B", STEP1, "{\"ENV\":\"dev\"}");

        List<CaseVersionFieldChange> changes = ActionJsonDiffCalculator.diff(from, to);

        assertEquals(1, changes.size(), "仅 title 不等，实际=" + changes);
        CaseVersionFieldChange title = find(changes, "title");
        assertEquals("登录A", title.before());
        assertEquals("登录B", title.after());
        assertTrue(title.label() != null && !title.label().isBlank(), "label 中文人话（FIELD_LABELS）");
    }

    @Test
    void steps增删改_单条steps变更值为动作列表() {
        String from = json("登录A", STEP1, "{\"ENV\":\"dev\"}");
        String to = json("登录A", STEP1 + "," + STEP2, "{\"ENV\":\"dev\"}");

        List<CaseVersionFieldChange> changes = ActionJsonDiffCalculator.diff(from, to);

        assertEquals(1, changes.size(), "steps 数量增删合并为一条，实际=" + changes);
        CaseVersionFieldChange steps = find(changes, "steps");
        assertInstanceOf(List.class, steps.before(), "before=动作列表供前端逐条高亮");
        assertInstanceOf(List.class, steps.after());
        assertEquals(1, ((List<?>) steps.before()).size());
        assertEquals(2, ((List<?>) steps.after()).size());
    }

    @Test
    void 多字段变更_顺序稳定() {
        String from = json("登录A", STEP1, "{\"ENV\":\"dev\"}");
        String to = json("登录B", STEP2, "{\"ENV\":\"test\"}");

        List<CaseVersionFieldChange> changes = ActionJsonDiffCalculator.diff(from, to);

        List<String> fields = changes.stream().map(CaseVersionFieldChange::field).toList();
        assertEquals(List.of("title", "steps", "env"), fields,
                "title/steps/env 三项报出（version/caseId/endType/setup 两侧相同不报）");
    }

    @Test
    void 相同内容_空列表() {
        String a = json("登录A", STEP1 + "," + STEP2, "{\"ENV\":\"dev\"}");
        assertEquals(List.of(), ActionJsonDiffCalculator.diff(a, a));
    }

    @Test
    void 键序不同值相同_不报变更() {
        String from = "{\"title\":\"T\",\"env\":{\"a\":1,\"b\":2}}";
        String to = "{\"env\":{\"b\":2,\"a\":1},\"title\":\"T\"}";
        assertEquals(List.of(), ActionJsonDiffCalculator.diff(from, to), "JSON 语义比较非文本比较");
    }

    @Test
    void 损坏入参_人话异常不静默() {
        assertThrows(IllegalArgumentException.class,
                () -> ActionJsonDiffCalculator.diff("not-json", json("A", STEP1, "{}")),
                "损坏 JSON 必须抛出（服务层转 corrupted 标记，IT-B5-01）");
        assertThrows(IllegalArgumentException.class,
                () -> ActionJsonDiffCalculator.diff(null, json("A", STEP1, "{}")));
        assertThrows(IllegalArgumentException.class,
                () -> ActionJsonDiffCalculator.diff(json("A", STEP1, "{}"), "123"));
    }

    @Test
    void metadata等未知顶层字段_并集比较() {
        String from = "{\"title\":\"T\",\"metadata\":{\"owner\":\"a\"}}";
        String to = "{\"title\":\"T\",\"metadata\":{\"owner\":\"b\"}}";

        List<CaseVersionFieldChange> changes = ActionJsonDiffCalculator.diff(from, to);

        assertEquals(1, changes.size());
        assertEquals("metadata", changes.get(0).field());
        assertInstanceOf(Map.class, changes.get(0).before());
    }
}
