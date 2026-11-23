package com.sixbean.testpike.admin.reports.api.controller;

import com.sixbean.testpike.admin.reports.api.dto.CaliberView;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.report.CaliberDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CaliberControllerTest {

    private final CaliberController controller = new CaliberController();

    @Test
    void 登录只读全量_八字段齐备() {
        Response<List<CaliberView>> resp = controller.calibers();
        List<CaliberView> views = resp.getData();
        assertEquals(CaliberDefinition.values().length, views.size(),
                "全量口径=注册表枚举逐条透出（IT-O10-01）");
        for (CaliberView v : views) {
            assertFalse(v.code().isBlank(), "口径键非空");
            assertFalse(v.displayName().isBlank(), "中文名非空");
            assertEquals(java.util.Arrays.stream(CaliberDefinition.values())
                            .filter(d -> d.code().equals(v.code())).findFirst().orElseThrow().version(),
                    v.version(), "口径版本=代码枚举同源透出（IT-O10-01 ③口径不可 API 写）");
            assertFalse(v.description().isBlank(), "一句话解释非空");
            assertEquals(v.displayName() + " " + v.version() + "=" + v.description(), v.hoverText(),
                    "hoverText=「通过率 v1.0=解释」格式（TC-O10-02）");
        }
        assertTrue(views.stream().anyMatch(v -> "pass-rate".equals(v.code())), "通过率口径在列");
    }

    @Test
    void 仅GET映射_无写端点泄露() {
        for (var m : CaliberController.class.getDeclaredMethods()) {
            for (var ann : m.getAnnotations()) {
                String name = ann.annotationType().getSimpleName();
                assertTrue("GetMapping".equals(name),
                        "口径端点不得出现非 GET 映射（口径=代码枚举不可 API 写，IT-O10-01）: " + name);
            }
        }
    }
}
