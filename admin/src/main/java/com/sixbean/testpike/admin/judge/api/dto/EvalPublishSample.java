package com.sixbean.testpike.admin.judge.api.dto;

import java.util.List;


public record EvalPublishSample(String input, String expected, List<String> referenceDocs,
                                String source, Integer sensitive) {
}
