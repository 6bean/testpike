package com.sixbean.testpike.core.perf.domain.component;

import java.util.List;
import java.util.Map;


public interface EngineAdapter {

    
    String compile(Map<String, Object> scenario);

    
    String launch(String artifact, Map<String, Object> runContext);

    
    List<PerfMetricFrame> collect(String engineSession);

    
    PerfAttribution attribute(Map<String, Object> run, List<PerfMetricFrame> frames);
}
