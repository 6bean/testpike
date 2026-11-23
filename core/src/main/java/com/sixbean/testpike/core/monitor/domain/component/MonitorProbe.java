package com.sixbean.testpike.core.monitor.domain.component;

import com.sixbean.testpike.core.monitor.infrastructure.dataobject.MonitorTarget;


public interface MonitorProbe {

    
    String supportedType();

    
    MonitorProbeResult probe(MonitorTarget target);
}
