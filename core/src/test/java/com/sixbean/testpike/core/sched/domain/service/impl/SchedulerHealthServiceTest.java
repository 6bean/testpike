package com.sixbean.testpike.core.sched.domain.service.impl;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.sched.api.dto.SchedulerHealthInfo;
import com.sixbean.testpike.core.sched.domain.service.SchedulerHealthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class SchedulerHealthServiceTest {

    private static final String PROBE_JOB = "tickHealthProbeJob";

    @Autowired
    private SchedulerHealthService healthService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanProbeRow() {
        jdbcTemplate.update("DELETE FROM shedlock WHERE name = ?", PROBE_JOB);
    }

    @Test
    void fullJobCatalogPresentWithChineseDescriptions() {
        PageResult<SchedulerHealthInfo> all = healthService.health(null, 1, 100);
        assertTrue(all.total() >= 16,
                "健康卡应恒含全部 16 个调度任务（实查 " + all.total() + "）");
        Map<String, SchedulerHealthInfo> byName = all.list().stream()
                .collect(Collectors.toMap(SchedulerHealthInfo::jobName, i -> i));
        for (String job : List.of("dailyDigestScan", "flakyDetectionDaily", "reviewRemindHourly",
                "costOverrideExpire", "executorOfflineSweep", "execTimeoutWatchdog",
                "envHealthScan", "reportWeeklyGenerate", "reportMonthlyGenerate",
                "reportRetentionDaily", "retentionCleanupDaily", "promptAdoptionSummaryDaily",
                "scheduleTriggerScan", "knowledgeReconcileDaily", "usageCalibrationRefresh")) {
            SchedulerHealthInfo info = byName.get(job);
            assertNotNull(info, "任务目录缺行: " + job);
            assertFalse(info.description().equals(job),
                    job + " 必须映射人话中文说明（红线10，禁裸 name dump）");
            assertTrue(info.description().chars().anyMatch(c -> c > 0x4E00 && c < 0x9FFF),
                    job + " 说明应为中文: " + info.description());
        }
    }

    @Test
    void neverRunJobShowsIdlePlaceholder() {
        jdbcTemplate.update("DELETE FROM shedlock WHERE name = ?", "reportMonthlyGenerate");
        PageResult<SchedulerHealthInfo> all = healthService.health("reportMonthlyGenerate", 1, 10);
        SchedulerHealthInfo row = all.list().get(0);
        assertEquals("reportMonthlyGenerate", row.jobName());
        assertEquals(null, row.lockedBy(), "无锁行=从未调度执行，持有者应为空");
        assertFalse(row.running(), "无锁行不得判执行中");
    }

    @Test
    void liveLockRowMergesWithHoldingState() {
        jdbcTemplate.update("""
                INSERT INTO shedlock (name, locked_by, lock_until, locked_at)
                VALUES (?, 'probe-host', now() + interval '1 hour', now() - interval '1 minute')
                """, PROBE_JOB);
        PageResult<SchedulerHealthInfo> hit = healthService.health(PROBE_JOB, 1, 10);
        assertEquals(1, hit.total());
        SchedulerHealthInfo running = hit.list().get(0);
        assertEquals("probe-host", running.lockedBy());
        assertTrue(running.running(), "lock_until 晚于当前应判执行中");
        assertNotNull(running.lockedAt(), "最近触发时刻应透出");

        jdbcTemplate.update("UPDATE shedlock SET lock_until = now() - interval '1 minute' WHERE name = ?",
                PROBE_JOB);
        SchedulerHealthInfo released = healthService.health(PROBE_JOB, 1, 10).list().get(0);
        assertFalse(released.running(), "lock_until 早于当前=锁已释放");
        assertEquals(PROBE_JOB, released.description());
    }

    @Test
    void keywordFilterMatchesNameAndDescription() {
        PageResult<SchedulerHealthInfo> byName = healthService.health("watchdog", 1, 10);
        assertTrue(byName.total() >= 1 && byName.list().stream()
                .allMatch(i -> i.jobName().toLowerCase().contains("watchdog")
                        || i.description().contains("watchdog")));
        PageResult<SchedulerHealthInfo> byDesc = healthService.health("超时", 1, 10);
        assertTrue(byDesc.total() >= 1, "中文说明关键词也应命中");
        assertTrue(byDesc.list().stream().allMatch(i -> i.description().contains("超时")));
    }

    @Test
    void paginationWindowAndTotal() {
        PageResult<SchedulerHealthInfo> p1 = healthService.health(null, 1, 5);
        assertEquals(5, p1.list().size(), "第一页应恰 5 行");
        PageResult<SchedulerHealthInfo> p2 = healthService.health(null, 2, 5);
        assertEquals(p1.total(), p2.total(), "翻页 total 恒定");
        List<String> names1 = p1.list().stream().map(SchedulerHealthInfo::jobName).toList();
        List<String> names2 = p2.list().stream().map(SchedulerHealthInfo::jobName).toList();
        assertTrue(names1.stream().noneMatch(names2::contains), "页间不得重复行");
    }

    // ---- server-side sorting of the composed health window.
    // The catalog x shedlock merge happens in memory (row count is constant at
    // job-catalog scale), so the whitelist gate orders the composed rows
    // fail-closed: only mapped keys drive the comparator, an injection-shaped
    // key is refused with the allowed list named in plain words, and the
    // three-argument historical call keeps the catalog insertion order.

    @Test
    void noSortKeepsCatalogInsertionOrder() {
        List<String> defaultNames = healthService.health(null, 1, 200).list().stream()
                .map(SchedulerHealthInfo::jobName).toList();
        List<String> explicitNull = healthService.health(null, 1, 200, null, null).list().stream()
                .map(SchedulerHealthInfo::jobName).toList();
        assertEquals(defaultNames, explicitNull, "null sortBy must equal the historical order");
        assertEquals("dailyDigestScan", defaultNames.get(0),
                "默认序=任务目录登记序，首个任务为 dailyDigestScan");
    }

    @Test
    void sortByJobNameBothDirectionsAreExactMirrors() {
        List<String> asc = healthService.health(null, 1, 200, "job_name", "asc").list().stream()
                .map(SchedulerHealthInfo::jobName).toList();
        List<String> desc = healthService.health(null, 1, 200, "job_name", "desc").list().stream()
                .map(SchedulerHealthInfo::jobName).toList();
        assertEquals(asc.stream().sorted().toList(), asc, "asc 必须整体升序");
        assertEquals(asc.reversed(), desc, "desc 必须是 asc 的镜像（任务名唯一无并列）");
    }

    @Test
    void sortedJobNamePagingContinuesTheOrder() {
        List<String> full = healthService.health(null, 1, 200, "job_name", "asc").list().stream()
                .map(SchedulerHealthInfo::jobName).toList();
        List<String> window = new java.util.ArrayList<>(
                healthService.health(null, 1, 5, "job_name", "asc").list().stream()
                        .map(SchedulerHealthInfo::jobName).toList());
        window.addAll(healthService.health(null, 2, 5, "job_name", "asc").list().stream()
                .map(SchedulerHealthInfo::jobName).toList());
        assertEquals(full.subList(0, 10), window, "翻页窗口必须延续排序序而非目录序");
    }

    @Test
    void sortByLockedAtAscSuffixesNeverRunRows() {
        List<SchedulerHealthInfo> rows = healthService.health(null, 1, 200, "locked_at", "asc").list();
        List<String> stamps = rows.stream().map(SchedulerHealthInfo::lockedAt).toList();
        int firstNull = stamps.indexOf(null);
        assertTrue(firstNull == -1 || stamps.subList(firstNull, stamps.size()).stream()
                .allMatch(java.util.Objects::isNull), "asc：无锁时刻行（从未执行）恒排在尾部后缀");
        List<String> nonNull = stamps.subList(0, firstNull == -1 ? stamps.size() : firstNull);
        assertEquals(nonNull.stream().sorted().toList(), nonNull, "有锁时刻按时间递增（字符串序=同格式时间序）");
    }

    @Test
    void offListAndInjectionSortKeysAreRefusedWithWhitelistNamed() {
        com.sixbean.testpike.common.exception.ServiceException thrown =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.sixbean.testpike.common.exception.ServiceException.class,
                        () -> healthService.health(null, 1, 20, "fire_time; DROP TABLE", null));
        assertTrue(thrown.getMessage().contains("job_name"),
                "报错须点名允许清单，实收: " + thrown.getMessage());
        org.junit.jupiter.api.Assertions.assertThrows(
                com.sixbean.testpike.common.exception.ServiceException.class,
                () -> healthService.health(null, 1, 20, "not_a_column", "asc"));
    }
}
