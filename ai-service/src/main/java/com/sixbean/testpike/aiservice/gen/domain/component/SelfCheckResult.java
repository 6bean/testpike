package com.sixbean.testpike.aiservice.gen.domain.component;

import com.sixbean.testpike.aiservice.gen.api.dto.GeneratedCase;
import com.sixbean.testpike.aiservice.gen.api.dto.GeneratedStep;

import java.util.ArrayList;
import java.util.List;


public record SelfCheckResult(boolean passed, List<String> errors) {
}
