package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmConfigBrief;
import com.sixbean.testpike.aiservice.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodic re-probe of ledger rows stuck at enforced = 0 (ticket-② .
 * <p>{@link LlmCapabilityProber} shipped in with no production caller
 * (dormant surface, recorded in the checkpoint); this scheduler is that caller.
 * Without it a row learned from one bad gateway era would steer every
 * schema-bearing call of that model into the tool-free form forever, even
 * after the endpoint learned to obey — the promotion truth can only come from
 * a live probe, and a live probe needs someone to ask on a schedule.
 * <p>Material rule: every probed pair rides its live tp_llm_config catalog row
 * resolved by model name (the prober javadoc forbids stub endpoints; core
 * outage = empty catalog = fail-closed skip, never a fabricated endpoint).
 * <p>Cost guard: the daily call budget ({@link #KEY_DAILY_MAX}) bounds real
 * provider calls per day; both K8 keys are read every tick (hot update, no
 * restart). The counter is in-process and bounded (one entry per day, the map
 * is cleared when the day rolls — R57 no unbounded accumulation).
 * <p>Scheduling posture: this class carries the @Scheduled/@SchedulerLock and
 * the testpike.scheduling.enabled switch only; the business logic stays in an
 * unconditional component so the 15 L2 contexts that disable scheduling keep
 * injecting the prober and the ledger (RetentionCleanupScheduler separation
 * precedent, pitfalls contract).
 */
@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class LlmCapabilityReprobeScheduler {

    private static final Logger log = LoggerFactory.getLogger(LlmCapabilityReprobeScheduler.class);

    /** K8 hot key: hours a row may sit unprobed before the next check (default 24). */
    public static final String KEY_INTERVAL_HOURS = "ai.llm.capability-reprobe-interval-hours";

    /** K8 hot key: max live probes per day, the anti-burn budget (default 20). */
    public static final String KEY_DAILY_MAX = "ai.llm.capability-reprobe-daily-max";

    /** Factory defaults, kept in sync with the seed rows (config-default gate). */
    public static final int DEFAULT_INTERVAL_HOURS = 24;
    public static final int DEFAULT_DAILY_MAX = 20;

    /** Inclusive intervals mirrored on the core write-side ranges. */
    static final int INTERVAL_MIN = 1;
    static final int INTERVAL_MAX = 168;
    static final int DAILY_MIN = 1;
    static final int DAILY_MAX_BOUND = 200;

    private final LlmOutputCapabilityLedger ledger;
    private final LlmCapabilityProber prober;
    private final CoreGateway coreGateway;
    private final JobRunner jobRunner;

    /** day -> probes spent (budget-finite: cleared on day roll). */
    private final Map<LocalDate, Integer> probesToday = new ConcurrentHashMap<>();
    private LocalDate budgetDay = LocalDate.now();

    public LlmCapabilityReprobeScheduler(LlmOutputCapabilityLedger ledger,
                                         LlmCapabilityProber prober,
                                         CoreGateway coreGateway,
                                         JobRunner jobRunner) {
        this.ledger = ledger;
        this.prober = prober;
        this.coreGateway = coreGateway;
        this.jobRunner = jobRunner;
    }

    /** Hourly tick at :25 (each tick re-reads both K8 keys; cron itself file-hot). */
    @SchedulerLock(name = "llmCapabilityReprobe", lockAtMostFor = "PT10M")
    @Scheduled(cron = "${testpike.llm.capability-reprobe-cron:0 25 * * * *}")
    public void reprobeHourly() {
        try {
            int probes = reprobeOnce();
            if (probes > 0) {
                log.info("能力台账复探轮完成：本轮探测 {} 项", probes);
            }
        } catch (RuntimeException roundFailed) {
            log.warn("能力台账复探轮失败（下一轮自动重试，探测幂等）: {}",
                    roundFailed.getMessage());
            jobRunner.alert("llmCapabilityReprobe", roundFailed);
        }
    }

    /**
     * One reprobe round (public for L2 direct call, same posture as
     * UsageCalibrationRefresher.refreshOnce).
     *
     * @return number of probes actually spent this round
     */
    public int reprobeOnce() {
        int intervalHours = SysConfigInts.parseInt(
                coreGateway.getSysConfig(KEY_INTERVAL_HOURS), INTERVAL_MIN, INTERVAL_MAX,
                DEFAULT_INTERVAL_HOURS);
        List<String[]> due = ledger.findStaleObservations(intervalHours);
        if (due.isEmpty()) {
            return 0;
        }
        int dailyMax = SysConfigInts.parseInt(
                coreGateway.getSysConfig(KEY_DAILY_MAX), DAILY_MIN, DAILY_MAX_BOUND,
                DEFAULT_DAILY_MAX);
        List<LlmConfigBrief> catalog = coreGateway.listEnabledLlmConfigs();
        if (catalog.isEmpty()) {
            // Fail-closed: without live catalog rows there is no legitimate
            // endpoint to probe (a stub would fabricate a verdict — P18).
            log.warn("能力台账复探本轮跳过：模型清单为空（core 不可达或全部停用），到期行 {} 项保持原状",
                    due.size());
            return 0;
        }
        LocalDate today = LocalDate.now();
        if (!today.equals(budgetDay)) {
            probesToday.clear();
            budgetDay = today;
        }
        int spent = probesToday.getOrDefault(today, 0);
        int probed = 0;
        for (String[] pair : due) {
            if (spent >= dailyMax) {
                log.info("能力台账复探已达当日预算上限 {}，剩余到期行顺延下一轮", dailyMax);
                break;
            }
            LlmConfigBrief row = catalog.stream()
                    .filter(c -> pair[0].equals(c.modelName()))
                    .findFirst().orElse(null);
            if (row == null || isBlank(row.baseUrl())) {
                log.info("能力台账复探跳过：模型 {} 在启用清单中无可用行（已停用或改名），不落任何判定",
                        pair[0]);
                continue;
            }
            LlmEndpointConfig endpoint = new LlmEndpointConfig(row.provider(), row.modelName(),
                    row.apiKey(), row.baseUrl(), LlmEndpointConfig.SOURCE_CHAIN,
                    row.promptLimitTokens(), row.id(), row.tokenizerFamily(),
                    row.cacheHint(), row.structuredOutput());
            prober.probeAndRecord(endpoint, pair[1]);
            spent++;
            probed++;
            probesToday.put(today, spent);
        }
        return probed;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
