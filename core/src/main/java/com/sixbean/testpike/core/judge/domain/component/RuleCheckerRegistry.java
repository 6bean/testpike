package com.sixbean.testpike.core.judge.domain.component;

import com.sixbean.testpike.common.exception.ValidationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;


@Component
public class RuleCheckerRegistry {

    private final Map<String, RuleChecker> checkers = new TreeMap<>();

    public RuleCheckerRegistry() {
        checkers.put("empty-output", RuleCheckerBuiltin::emptyOutput);
        checkers.put("json-invalid", RuleCheckerBuiltin::jsonInvalid);
        checkers.put("json-array", RuleCheckerBuiltin::jsonArray);
        checkers.put("length-range", RuleCheckerBuiltin::lengthRange);
        checkers.put("sensitive-words", RuleCheckerBuiltin::sensitiveWords);
        checkers.put("regex-forbidden", RuleCheckerBuiltin::regexForbidden);
        checkers.put("regex-required", RuleCheckerBuiltin::regexRequired);
        checkers.put("required-substrings", RuleCheckerBuiltin::requiredSubstrings);
        checkers.put("placeholder-left", RuleCheckerBuiltin::placeholderLeft);
        checkers.put("markdown-fence-leak", RuleCheckerBuiltin::markdownFenceLeak);
        checkers.put("duplicate-lines", RuleCheckerBuiltin::duplicateLines);
        checkers.put("truncated-tail", RuleCheckerBuiltin::truncatedTail);
    }

    
    public RuleChecker resolve(String checkerName) {
        if (checkerName == null || checkerName.isBlank()) {
            throw new ValidationException("规则项缺少检查器名（checker 不能为空；可用检查器："
                    + String.join(" / ", checkers.keySet()) + "）");
        }
        RuleChecker checker = checkers.get(checkerName.strip());
        if (checker == null) {
            throw new ValidationException("未知的规则检查器「" + checkerName
                    + "」（可用检查器：" + String.join(" / ", checkers.keySet()) + "）");
        }
        return checker;
    }

    
    public List<String> names() {
        return List.copyOf(checkers.keySet());
    }
}
