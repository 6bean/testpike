package com.sixbean.testpike.core.monitor.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixbean.testpike.core.dbconnection.api.dto.ConnectivityTestInfo;
import com.sixbean.testpike.core.dbconnection.domain.service.DbConnectionService;
import com.sixbean.testpike.core.monitor.infrastructure.dataobject.MonitorTarget;
import org.springframework.stereotype.Component;


@Component
public class DbProbe implements MonitorProbe {

    private final MonitorProbeSupport support;
    private final DbConnectionService dbConnectionService;

    public DbProbe(MonitorProbeSupport support, DbConnectionService dbConnectionService) {
        this.support = support;
        this.dbConnectionService = dbConnectionService;
    }

    @Override
    public String supportedType() {
        return "DB";
    }

    @Override
    public MonitorProbeResult probe(MonitorTarget target) {
        JsonNode config = support.parseProbeConfig(target.getProbeConfig());
        long connectionId = config.path("connectionId").asLong(0);
        if (connectionId <= 0) {
            return MonitorProbeResult.fail(null, "探针配置缺少 connectionId（请先在数据源管理登记连接）");
        }
        long start = System.nanoTime();
        try {
            ConnectivityTestInfo info = dbConnectionService.test(target.getProjectId(), connectionId);
            int latency = support.elapsedMs(start);
            return info.passed()
                    ? MonitorProbeResult.ok(latency)
                    : MonitorProbeResult.fail(latency, "数据库连通失败：" + info.message());
        } catch (Exception e) {
            return MonitorProbeResult.fail(support.elapsedMs(start),
                    "数据库探测失败：" + support.brief(e));
        }
    }
}
