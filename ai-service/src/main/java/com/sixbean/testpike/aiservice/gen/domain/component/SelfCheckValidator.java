package com.sixbean.testpike.aiservice.gen.domain.component;

import com.sixbean.testpike.aiservice.gen.api.dto.GeneratedCase;
import com.sixbean.testpike.aiservice.gen.api.dto.GeneratedStep;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


public class SelfCheckValidator {

    
    public static final int MAX_STEPS = 50;

    public SelfCheckResult check(GeneratedCase c) {
        List<String> errors = new ArrayList<>();
        if (c.title() == null || c.title().isBlank()) {
            errors.add("测试目标为空");
        }
        if (c.expectedResult() == null || c.expectedResult().isBlank()) {
            errors.add("预期结果为空");
        }
        if (c.steps() == null || c.steps().isEmpty()) {
            errors.add("操作步骤为空");
        } else {
            if (c.steps().size() > MAX_STEPS) {
                errors.add("步骤数超过上限 50");
            }
            Set<Integer> seen = new HashSet<>();
            int expectNo = 1;
            for (GeneratedStep step : c.steps()) {
                if (step.stepNo() != expectNo) {
                    errors.add("步骤编号断裂（应为 " + expectNo + " 实际 " + step.stepNo() + "）");
                    break;
                }
                expectNo++;
                if (!seen.add(step.stepNo())) {
                    errors.add("步骤编号重复 " + step.stepNo());
                    break;
                }
                if (step.description() == null || step.description().isBlank()) {
                    errors.add("步骤 " + step.stepNo() + " 描述为空");
                }
                if (step.isAssert() != 0 && step.isAssert() != 1) {
                    errors.add("步骤 " + step.stepNo() + " isAssert 非法（仅 0=操作 1=断言）");
                }
            }
        }
        return new SelfCheckResult(errors.isEmpty(), errors);
    }
}
