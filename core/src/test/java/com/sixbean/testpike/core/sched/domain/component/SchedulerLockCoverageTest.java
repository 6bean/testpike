package com.sixbean.testpike.core.sched.domain.component;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class SchedulerLockCoverageTest {

    /** Total @Scheduled methods across the two modules (core 31 + ai-service 3). Grew stepwise with each
     *  scheduler added; latest increments: platform release-feed candidate probe (clusterReleaseCheck) -> 28,
     *  mock space expiry sweep -> 29, mock hit retention sweep -> 30, document-extract stall sweep -> 31,
     *  MCP knowledge-sync scan -> 32, flaky-probe sweep -> 33, correction distillation daily (ai-service) -> 34,
     *  interface-catalog refresh (the pass-through door's own rebuild, core) -> 35,
     *  dead-letter auto-replay sweep (core) -> 36, login-state keep-alive tick and the
     *  fresh-install repair batch (watermark sampler, stall sweeper, survey attribution
     *  job, credential screenshot sweep) -> 48, import-suggestion retention trim
     *  (mockSpecImportVerdictTrim) -> 49.
     *  Changing this constant = intentional scheduler change. */
    private static final int EXPECTED_SCHEDULED_TOTAL = 49;

    @Test
    void everyScheduledMethodCarriesSchedulerLock() throws IOException {
        Path root = locateRepoRoot();
        List<Path> sources = new ArrayList<>();
        collectScheduledSources(root.resolve("core/src/main/java"), sources);
        collectScheduledSources(root.resolve("ai-service/src/main/java"), sources);

        int scheduledTotal = 0;
        for (Path file : sources) {
            List<String> lines = Files.readAllLines(file);
            int scheduledCount = 0;
            int lockCount = 0;
            List<Integer> scheduledAt = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.trim().startsWith("@Scheduled")) {
                    scheduledCount++;
                    scheduledAt.add(i);
                }
                if (line.trim().startsWith("@SchedulerLock")) {
                    lockCount++;
                }
            }
            scheduledTotal += scheduledCount;
            final int sc = scheduledCount;
            final int lc = lockCount;
            assertEquals(sc, lc,
                    () -> file + "：@Scheduled=" + sc + " @SchedulerLock=" + lc + " 数量不齐");
            for (int idx : scheduledAt) {
                boolean paired = false;
                for (int look = Math.max(0, idx - 4); look <= Math.min(idx + 4, lines.size() - 1); look++) {
                    if (look != idx && lines.get(look).trim().startsWith("@SchedulerLock")) {
                        paired = true;
                        break;
                    }
                }
                final int at = idx;
                assertTrue(paired, () -> file + ":" + (at + 1) + " @Scheduled 相邻 4 行内缺 @SchedulerLock");
            }
        }
        assertEquals(EXPECTED_SCHEDULED_TOTAL, scheduledTotal,
                "@Scheduled 总数变化=调度器扩容/收缩，请同步核对加锁与告警接线后更新期望值");
    }

    
    private void collectScheduledSources(Path tree, List<Path> out) throws IOException {
        try (var walk = Files.walk(tree)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains("@Scheduled");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .forEach(out::add);
        }
    }

    
    private Path locateRepoRoot() {
        return com.sixbean.testpike.core.support.RepoRootLocator.locate();
    }
}
