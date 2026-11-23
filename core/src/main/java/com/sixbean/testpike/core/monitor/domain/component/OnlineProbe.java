package com.sixbean.testpike.core.monitor.domain.component;

import com.sixbean.testpike.core.monitor.infrastructure.dataobject.MonitorTarget;
import org.springframework.stereotype.Component;


@Component
public class OnlineProbe implements MonitorProbe {

    private final HttpProbe httpProbe;

    public OnlineProbe(HttpProbe httpProbe) {
        this.httpProbe = httpProbe;
    }

    @Override
    public String supportedType() {
        return "ONLINE";
    }

    @Override
    public MonitorProbeResult probe(MonitorTarget target) {
        return httpProbe.exchange(target);
    }
}
