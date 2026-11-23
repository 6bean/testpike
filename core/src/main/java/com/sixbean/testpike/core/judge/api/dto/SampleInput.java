package com.sixbean.testpike.core.judge.api.dto;

import java.util.List;


public record SampleInput(String input, String expected, List<String> referenceDocs,
                          String source, Integer sensitive) {
}
