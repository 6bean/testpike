package com.sixbean.testpike.core.perf.domain.component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * D3 engine-caliber disclosure, single source (W-d third engine).
 * <p>JMeter presses a declared RPS through VU (thread-group) driver pressure
 * and per-second throughput-timer smoothing, so its instantaneous rate only
 * approximates the target. The wording reuses
 * {@link JmxTemplate#APPROXIMATION_NOTE} verbatim — the same string embedded
 * in every JMX artifact — and appends the VU driver caliber once; wizard
 * annotations and report footnotes both consume this component, never a
 * paraphrased second copy (R40 single source).
 * <p>Exact-schedule engines (K6/GATLING) honestly ship no caveat: fabricating
 * approximation wording for them would be disclosure noise. The catalogue
 * projects the dispatch value domain itself (PerfRunDispatchSupport#
 * ENGINE_VALUES) so the wizard option list and the disclosure face can never
 * disagree about which engines exist, and it carries only engine+caveat
 * fields — no AI-family configuration keys leave this door.
 */
public final class PerfEngineDisclosure {

    /** VU driver caliber, appended after the verbatim JMX note. */
    private static final String JMETER_VU_CALIBER =
            "引擎侧压取由 VU（线程组）与节拍驱动：目标 RPS 经线程数与每秒节拍近似逼近，非精确到达率调度。";

    private PerfEngineDisclosure() {
    }

    /** Disclosure wording for one engine value (null = honest absence). */
    public static String caveatFor(String engine) {
        if ("JMETER".equals(engine)) {
            return JmxTemplate.APPROXIMATION_NOTE + JMETER_VU_CALIBER;
        }
        return null;
    }

    /** Wizard engine-option supply: one row per declared engine, values
     *  single-sourced from the dispatch domain, sorted for stable ordering. */
    public static List<Map<String, Object>> catalogue() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String engine : new TreeSet<>(PerfRunDispatchSupport.ENGINE_VALUES)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("engine", engine);
            row.put("caveat", caveatFor(engine));
            rows.add(row);
        }
        return rows;
    }
}
