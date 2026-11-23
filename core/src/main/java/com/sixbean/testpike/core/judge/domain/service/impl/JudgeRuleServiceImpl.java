package com.sixbean.testpike.core.judge.domain.service.impl;

import com.sixbean.testpike.core.judge.api.dto.JudgeRuleCheckInput;
import com.sixbean.testpike.core.judge.api.dto.RuleVerdict;
import com.sixbean.testpike.core.judge.domain.component.JudgeRuleEngine;
import com.sixbean.testpike.core.judge.domain.service.JudgeRuleService;
import org.springframework.stereotype.Service;


@Service
public class JudgeRuleServiceImpl implements JudgeRuleService {

    private final JudgeRuleEngine ruleEngine;

    public JudgeRuleServiceImpl(JudgeRuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
    }

    @Override
    public RuleVerdict check(JudgeRuleCheckInput input) {
        return ruleEngine.run(input.rules(), input.output(), input.expected());
    }
}
