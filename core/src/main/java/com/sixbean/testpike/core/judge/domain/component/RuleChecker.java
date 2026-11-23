package com.sixbean.testpike.core.judge.domain.component;

import java.util.Map;


public interface RuleChecker {

    
    String check(String output, String expected, Map<String, Object> params);
}
