package com.sixbean.testpike.core.monitor.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixbean.testpike.common.util.TlsPolicy;
import com.sixbean.testpike.core.monitor.infrastructure.dataobject.MonitorTarget;
import com.sixbean.testpike.core.project.domain.component.EnvTlsSupport;
import com.sixbean.testpike.core.project.domain.component.TlsAwareHttpSupport;
import com.sixbean.testpike.core.project.domain.component.TlsHttpResult;
import org.springframework.stereotype.Component;


@Component
public class HttpProbe implements MonitorProbe {

    static final int PROBE_TIMEOUT_MS = 5000;

    private final MonitorProbeSupport support;
    private final TlsAwareHttpSupport tlsHttp;
    private final EnvTlsSupport envTlsSupport;

    public HttpProbe(MonitorProbeSupport support, TlsAwareHttpSupport tlsHttp,
                     EnvTlsSupport envTlsSupport) {
        this.support = support;
        this.tlsHttp = tlsHttp;
        this.envTlsSupport = envTlsSupport;
    }

    @Override
    public String supportedType() {
        return "HTTP";
    }

    @Override
    public MonitorProbeResult probe(MonitorTarget target) {
        return exchange(target);
    }

    
    MonitorProbeResult exchange(MonitorTarget target) {
        JsonNode config = support.parseProbeConfig(target.getProbeConfig());
        String url = config.path("url").asText("").trim();
        if (url.isEmpty()) {
            return MonitorProbeResult.fail(null, "探针配置缺少 url");
        }
        TlsPolicy policy = envTlsSupport.resolvePolicyFailSafe(support.envConfigJson(target.getEnvId()));
        long start = System.nanoTime();
        TlsHttpResult result;
        try {
            result = tlsHttp.get(url, policy, PROBE_TIMEOUT_MS);
        } catch (Exception e) {
            return MonitorProbeResult.fail(support.elapsedMs(start), "连通检查失败：" + support.brief(e));
        }
        int latency = support.elapsedMs(start);
        if (result.status() < 200 || result.status() >= 300) {
            return MonitorProbeResult.fail(latency, "连通检查失败：HTTP " + result.status());
        }
        String expectBody = config.path("expect").path("body").asText("");
        if (!expectBody.isEmpty() && !result.body().contains(expectBody)) {
            return MonitorProbeResult.fail(latency,
                    "响应不含预期片段「" + expectBody + "」，页面可能已改版");
        }
        return MonitorProbeResult.ok(latency);
    }
}
