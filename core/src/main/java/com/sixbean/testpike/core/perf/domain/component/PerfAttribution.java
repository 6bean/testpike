package com.sixbean.testpike.core.perf.domain.component;

import java.util.List;


public record PerfAttribution(boolean passed, String conclusion, List<String> breachedGates) {
}
