package com.sixbean.testpike.core.perf.domain.component;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-language histogram-boundary consistency test .
 * <p>The executor mirrors the 27 histogram upper bounds as HIST_BOUNDARIES in
 * executor/perf_worker.py (the worker packs counts per bucket at collection time;
 * core merges the arrays for full-run percentiles). Two languages, one contract —
 * any drift silently corrupts every recomputed percentile, so this test parses the
 * Python constant and pins it against {@link PerfHistogram#boundaries}.
 * <p>Mirrors the O10 inline-mirror discipline (SQL caliber carries the same array,
 * kept in step by this style of gate). Repository layout is fixed (monorepo), so a
 * missing executor file fails the build instead of quietly skipping.
 */
class PerfHistogramMirrorConsistencyTest {

    /** Locate the executor worker module relative to either module or repo root cwd. */
    private static Path executorWorker() {
        for (String candidate : new String[]{
                "../executor/perf_worker.py", "executor/perf_worker.py"}) {
            Path p = Paths.get(candidate);
            if (Files.exists(p)) {
                return p;
            }
        }
        throw new IllegalStateException(
                "executor/perf_worker.py not found from cwd " + Paths.get(".").toAbsolutePath()
                        + " — the histogram mirror guard cannot run (layout drift?)");
    }

    @Test
    void executor_histogram_mirror_matches_core_boundaries() throws IOException {
        String source = Files.readString(executorWorker());
        Matcher m = Pattern.compile(
                "HIST_BOUNDARIES\\s*=\\s*\\(([^)]*)\\)", Pattern.DOTALL).matcher(source);
        assertThat(m.find()).as("HIST_BOUNDARIES tuple present in perf_worker.py").isTrue();

        String[] raw = m.group(1).replace("\n", " ").split(",");
        double[] mirror = new double[raw.length];
        int n = 0;
        for (String token : raw) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            mirror[n++] = Double.parseDouble(t);
        }
        double[] core = PerfHistogram.boundaries();
        assertThat(n).as("mirror bucket count equals core contract length")
                .isEqualTo(core.length);
        assertThat(java.util.Arrays.copyOf(mirror, n)).containsExactly(core);
    }
}
