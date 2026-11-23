package com.sixbean.testpike.core.judge.domain.component;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.judge.api.dto.JudgeRuleItem;
import com.sixbean.testpike.core.judge.api.dto.RuleVerdict;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Component
public class JudgeRuleEngine {

    private static final String ACTION_ZERO = "zero";
    private static final String ACTION_CAP = "cap";

    private final RuleCheckerRegistry registry;

    public JudgeRuleEngine(RuleCheckerRegistry registry) {
        this.registry = registry;
    }

    
    public RuleVerdict run(List<JudgeRuleItem> rules, String output, String expected) {
        if (rules == null || rules.isEmpty()) {
            return new RuleVerdict(null, List.of(), false);
        }
        String text = output == null ? "" : output;
        List<String> hits = new ArrayList<>();
        int score = 100;
        for (JudgeRuleItem item : rules) {
            RuleChecker checker = registry.resolve(item.checker());
            Map<String, Object> params = item.params() == null ? Map.of() : item.params();
            String action = actionOf(params, item.checker());
            int cap = ACTION_CAP.equals(action) ? capScoreOf(params, item.checker()) : score;
            String reason = checker.check(text, expected, params);
            if (reason == null) {
                continue;
            }
            hits.add(item.checker() + ":" + reason);
            if (ACTION_ZERO.equals(action)) {
                return new RuleVerdict(0, hits, true);
            }
            score = Math.min(score, cap);
        }
        return new RuleVerdict(score, hits, false);
    }

    private static String actionOf(Map<String, Object> params, String checkerName) {
        Object raw = params.get("action");
        String action = raw == null ? ACTION_ZERO : String.valueOf(raw).strip();
        if (!ACTION_ZERO.equals(action) && !ACTION_CAP.equals(action)) {
            throw new ValidationException("规则「" + checkerName + "」的 action 非法：" + raw
                    + "（可选 zero=命中短路 0 分 / cap=封上限分续跑）");
        }
        return action;
    }

    private static int capScoreOf(Map<String, Object> params, String checkerName) {
        Integer cap = RuleParams.intOpt(params, "capScore");
        if (cap == null || cap < 0 || cap > 100) {
            throw new ValidationException("规则「" + checkerName + "」action=cap 必须配 capScore（0~100，当前="
                    + params.get("capScore") + "）");
        }
        return cap;
    }
}
