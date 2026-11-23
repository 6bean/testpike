package com.sixbean.testpike.core.judge.domain.service;

import com.sixbean.testpike.core.judge.api.dto.JudgeRuleCheckInput;
import com.sixbean.testpike.core.judge.api.dto.RuleVerdict;


public interface JudgeRuleService {

    
    RuleVerdict check(JudgeRuleCheckInput input);
}
