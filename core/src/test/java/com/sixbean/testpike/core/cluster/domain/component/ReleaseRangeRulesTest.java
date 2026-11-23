package com.sixbean.testpike.core.cluster.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ReleaseRangeRulesTest {

    @Test
    void within_inclusiveBounds() {
        assertTrue(ReleaseRangeRules.within("1.3.0", "1.3.0", "1.5.0"), "下界含边界");
        assertTrue(ReleaseRangeRules.within("1.5.0", "1.3.0", "1.5.0"), "上界含边界");
        assertTrue(ReleaseRangeRules.within("1.4.2", "1.3.0", "1.5.0"), "界内放行");
        assertFalse(ReleaseRangeRules.within("1.2.9", "1.3.0", "1.5.0"), "低于下界=不兼容");
        assertFalse(ReleaseRangeRules.within("1.5.1", "1.3.0", "1.5.0"), "高于上界=未经背书不兼容");
    }

    @Test
    void within_nullBounds_meanNoGate() {
        assertTrue(ReleaseRangeRules.within("0.0.1", null, null), "双界缺省=不设闸（未导入态不拦人）");
        assertTrue(ReleaseRangeRules.within("1.4.0", null, "1.5.0"), "缺下界只闸上界：上界内放行");
        assertFalse(ReleaseRangeRules.within("1.6.0", null, "1.5.0"), "缺下界仍闸上界：超上界不兼容");
        assertTrue(ReleaseRangeRules.within("1.6.0", "1.5.0", null), "缺上界只闸下界：下界上放行");
        assertFalse(ReleaseRangeRules.within("1.4.0", "1.5.0", null), "缺上界仍闸下界：低于下界不兼容");
        assertTrue(ReleaseRangeRules.within("1.4.0", "  ", " "), "空白界串等同缺省");
    }

    @Test
    void within_freeTextVersion_extractsFirstNumber() {
        assertTrue(ReleaseRangeRules.within("v22.3 (x64)", "22.0", "22.5"),
                "自由文本带版本=按提取值判（提取口径与台账判定谱同源）");
    }

    @Test
    void within_shortSegmentsPadded() {
        assertTrue(ReleaseRangeRules.within("1.5", "1.5.0", "1.5.9"), "短位补零：1.5==1.5.0 含下界");
        assertFalse(ReleaseRangeRules.within("1.5.1", "1.3", "1.5"), "1.5.1 超前 1.5=界外");
    }
}
