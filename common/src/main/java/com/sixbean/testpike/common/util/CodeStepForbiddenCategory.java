package com.sixbean.testpike.common.util;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


final class CodeStepForbiddenCategory {

    private final String label;
    private final List<Pattern> patterns;

    CodeStepForbiddenCategory(String label, String... words) {
        this.label = label;
        this.patterns = Arrays.stream(words)
                .map(word -> Pattern.compile(word.indexOf('\\') >= 0 ? word : "\\b" + word + "\\b"))
                .toList();
    }

    
    String label() {
        return label;
    }

    
    String firstHit(String code) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(code);
            if (matcher.find()) {
                return matcher.group();
            }
        }
        return null;
    }
}
