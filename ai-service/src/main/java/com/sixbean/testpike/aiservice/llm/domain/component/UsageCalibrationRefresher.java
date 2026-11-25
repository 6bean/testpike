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
                log.info("E11 C-coefficient calibration loop refresh done: applied={}", applied);
            }
        } catch (Exception e) {
            log.warn("E11 C-coefficient calibration loop refresh failed (auto retry next round): {}", e.getMessage());
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
                log.warn("E11 C-coefficient loop: aggregate row fields missing or non-positive, skipped row={}", row);
                continue;
            }
            if (count.longValue() < minSamples) {
                continue;
            }
            double coef = usage.doubleValue() / est.doubleValue();
            if (coef < MIN_COEF || coef > MAX_COEF || !Double.isFinite(coef)) {
                log.warn("E11 C-coefficient loop: coefficient outside plausible range [{}, {}], refused to apply (upstream data anomaly): "
                        + "model={} coef={}", MIN_COEF, MAX_COEF, model, coef);
                continue;
            }
            TokenEstimatorRegistry.registerCoefficient(model, coef);
            applied++;
            log.info("E11 C-coefficient loop: model={} samples={} est={} usage={} coef={} registered",
                    model, count.longValue(), est.doubleValue(), usage.doubleValue(), coef);
        }
        log.debug("E11 C-coefficient loop refresh: window_days={} rows={} applied={}",
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
