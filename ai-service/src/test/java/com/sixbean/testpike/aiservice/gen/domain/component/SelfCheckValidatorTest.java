package com.sixbean.testpike.aiservice.gen.domain.component;

import com.sixbean.testpike.aiservice.gen.api.dto.GeneratedCase;
import com.sixbean.testpike.aiservice.gen.api.dto.GeneratedStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class SelfCheckValidatorTest {

    private final SelfCheckValidator validator = new SelfCheckValidator();

    private GeneratedCase caseOf(List<GeneratedStep> steps) {
        return new GeneratedCase("标题", "前置", "预期结果", steps, 1, 2, "web");
    }

    @Test
    void check_完整用例_通过() {
        List<GeneratedStep> steps = List.of(
                new GeneratedStep(1, "输入账号", 0), new GeneratedStep(2, "出现登录成功", 1));
        assertTrue(validator.check(caseOf(steps)).passed());
    }

    @Test
    void check_缺预期结果_失败() {
        GeneratedCase c = new GeneratedCase("标题", "前置", "", List.of(new GeneratedStep(1, "d", 0)), 1, 2, "web");
        assertFalse(validator.check(c).passed());
    }

    @Test
    void check_步骤编号断裂_失败() {
        List<GeneratedStep> steps = List.of(
                new GeneratedStep(1, "d1", 0), new GeneratedStep(3, "d3", 0));
        assertFalse(validator.check(caseOf(steps)).passed());
    }

    @Test
    void check_步骤编号从0开始_失败() {
        List<GeneratedStep> steps = List.of(new GeneratedStep(0, "d", 0));
        assertFalse(validator.check(caseOf(steps)).passed());
    }

    @Test
    void check_步骤为空_失败() {
        assertFalse(validator.check(caseOf(List.of())).passed());
    }

    @Test
    void check_步骤超50条_失败() {
        List<GeneratedStep> steps = java.util.stream.IntStream.range(1, 52)
                .mapToObj(i -> new GeneratedStep(i, "d" + i, 0)).toList();
        assertFalse(validator.check(caseOf(steps)).passed());
    }

    @Test
    void check_isAssert非法值_失败() {
        List<GeneratedStep> steps = List.of(new GeneratedStep(1, "d", 2));
        assertFalse(validator.check(caseOf(steps)).passed());
    }
}
