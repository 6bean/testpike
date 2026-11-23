package com.sixbean.testpike.core.cases.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class FeedbackTypeTest {

    @Test
    void sixFrozenTypes_codeToLabel() {
        assertEquals("步骤不准确", FeedbackType.from("step_inaccurate").label());
        assertEquals("预期结果错误", FeedbackType.from("expected_wrong").label());
        assertEquals("选择器问题", FeedbackType.from("selector_issue").label());
        assertEquals("逻辑遗漏", FeedbackType.from("logic_missing").label());
        assertEquals("措辞不准确", FeedbackType.from("wording").label());
        assertEquals("定位错误", FeedbackType.from("locate_error").label());
    }

    @Test
    void from_非法编码_40001含中文合法清单() {
        ServiceException ex = assertThrows(ServiceException.class, () -> FeedbackType.from("broken_flow"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("步骤不准确"),
                "非法值提示须含中文合法清单：%s".formatted(ex.getMessage()));
        assertTrue(ex.getMessage().contains("broken_flow"), "提示点名非法值本身");
    }

    @Test
    void labelsOf_保序去重_非法即40001() {
        List<String> labels = FeedbackType.labelsOf(
                List.of("logic_missing", "step_inaccurate", "logic_missing"));
        assertEquals(List.of("逻辑遗漏", "步骤不准确"), labels, "保序去重（IT-D1-01）");

        assertThrows(ServiceException.class,
                () -> FeedbackType.labelsOf(List.of("logic_missing", "unknown_type")));
    }

    @Test
    void labelsOf_空与null安全() {
        assertEquals(List.of(), FeedbackType.labelsOf(null));
        assertEquals(List.of(), FeedbackType.labelsOf(List.of()));
    }
}
