package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class UsageCalibrationRefresher {

    private static final Logger log = LoggerFactory.getLogger(UsageCalibrationRefresher.class);

    
    static final double MIN_COEF = 0.2;
    static final double MAX_COEF = 5.0;

    private final CoreGateway coreGateway;
    private final JobRunner jobRunner;
    private final int windowDays;
    private final int minSamples;

    public UsageCalibrationRefresher(
            CoreGateway coreGateway,
            JobRunner jobRunner,
            @Value("${testpike.usage.calibration-refresh-days:90}") int windowDays,
            @Value("${testpike.usage.calibration-refresh-min-samples:5}") int minSamples) {
        this.coreGateway = coreGateway;
        this.jobRunner = jobRunner;
        this.windowDays = windowDays;
        this.minSamples = Math.max(1, minSamples);
    }

    
    @SchedulerLock(name = "usageCalibrationRefresh", lockAtMostFor = "PT30M")
    @Scheduled(cron = "${testpike.usage.calibration-refresh-cron:0 0 4 * * *}")
    public void refresh() {
        try {
            int applied = refreshOnce();
            if (applied > 0) {
                log.info("E11 C 系数回路刷新完成：applied={}", applied);
            }
        } catch (Exception e) {
            log.warn("E11 C 系数回路刷新失败（下轮自动重试）: {}", e.getMessage());
            jobRunner.alert("usageCalibrationRefresh", e);
        }
    }

    
    public int refreshOnce() {
        List<Map<String, Object>> rows = coreGateway.listUsageCalibration(windowDays);
        int applied = 0;
        for (Map<String, Object> row : rows) {
            String model = str(row.get("model"));
            Number count = num(row.get("sampleCount"));
            Number est = num(row.get("avgEstTokens"));
            Number usage = num(row.get("avgUsageTokens"));
            if (model == null || model.isBlank() || count == null || est == null || usage == null
                    || est.doubleValue() <= 0 || usage.doubleValue() <= 0) {
                log.warn("E11 C 系数回路：聚合行字段缺失/非正，跳过 row={}", row);
                continue;
            }
            if (count.longValue() < minSamples) {
                continue;
            }
            double coef = usage.doubleValue() / est.doubleValue();
            if (coef < MIN_COEF || coef > MAX_COEF || !Double.isFinite(coef)) {
                log.warn("E11 C 系数回路：系数越出合理区间 [{}, {}] 拒装（上游数据异常）："
                        + "model={} coef={}", MIN_COEF, MAX_COEF, model, coef);
                continue;
            }
            TokenEstimatorRegistry.registerCoefficient(model, coef);
            applied++;
            log.info("E11 C 系数回路：model={} samples={} est={} usage={} coef={} 已注册",
                    model, count.longValue(), est.doubleValue(), usage.doubleValue(), coef);
        }
        log.debug("E11 C 系数回路刷新：window_days={} rows={} applied={}",
                windowDays, rows.size(), applied);
        return applied;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Number num(Object v) {
        return v instanceof Number n ? n : null;
    }
}
