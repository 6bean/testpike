package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorCompatView;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseRegisterInput;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rows for this suite's name family = no family entries in the diff (the shared
 * dev ledger also holds live foreign rows; a global empty list is not safely
 * observable without wiping them).
 */
@SpringBootTest(classes = CoreApplication.class)
class ReleaseManifestCompatDiffTest {

    @Autowired
    private ReleaseManifestService manifestService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Fixture-scoped teardown : the executor ledger is shared with
    // the live runner heartbeat row and concurrent suites, so the blanket
    // TRUNCATE is replaced by (1) a startup sweep of this suite's own executor
    // name family — crashed-run leftovers would otherwise pollute the diff —
    // and (2) deletion of exactly the ids registered through insertExecutor.
    private final java.util.List<Long> trackedExecutors =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void sweepFamilyResidue() {
        jdbcTemplate.execute("DELETE FROM tp_release_manifest");
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE executor_id IN"
                + " (SELECT id FROM tp_executor WHERE name LIKE 'compat-%')");
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'compat-%'");
    }

    @AfterEach
    void dropRunRows() {
        jdbcTemplate.execute("DELETE FROM tp_release_manifest");
        SharedDbFixtureSweep.byIds(jdbcTemplate, "tp_executor", trackedExecutors);
        trackedExecutors.clear();
    }

    private void insertExecutor(String name, String version) {
        Long id = jdbcTemplate.queryForObject("INSERT INTO tp_executor (name, capacity, running_cnt,"
                + " status, version, last_heartbeat) VALUES (?, 2, 0, 1, ?, now()) RETURNING id",
                Long.class, name, version);
        if (id != null) {
            trackedExecutors.add(id);
        }
    }

    /** The shared dev ledger also holds foreign live rows; scope assertions to this suite's family. */
    private List<ExecutorCompatView> family(List<ExecutorCompatView> diff) {
        return diff.stream().filter(d -> d.name().startsWith("compat-")).toList();
    }

    private void manifest(String min, String max) {
        manifestService.register(new ReleaseRegisterInput("2.0.0",
                "[]", "[]", min, max, null, "manual", null, null));
    }

    @Test
    void compatDiff_区间内绿_区间外红带人话理由() {
        manifest("1.3.0", "1.5.0");
        insertExecutor("compat-ok-node", "1.4.0");
        insertExecutor("compat-old-node", "1.2.0");
        insertExecutor("compat-new-node", "1.6.0");

        List<ExecutorCompatView> diff = family(manifestService.compatDiff());
        assertEquals(3, diff.size());

        ExecutorCompatView ok = diff.stream().filter(d -> d.name().equals("compat-ok-node"))
                .findFirst().orElseThrow();
        assertTrue(ok.compatible(), "区间内=绿");
        assertFalse(ok.compatible() && ok.reason() != null, "绿态不谎报理由");

        ExecutorCompatView old = diff.stream().filter(d -> d.name().equals("compat-old-node"))
                .findFirst().orElseThrow();
        assertFalse(old.compatible(), "低于下界=红");
        assertTrue(old.reason() != null && old.reason().contains("1.3"),
                "红态人话理由须点名所需区间：" + old.reason());

        ExecutorCompatView newer = diff.stream().filter(d -> d.name().equals("compat-new-node"))
                .findFirst().orElseThrow();
        assertFalse(newer.compatible(), "高于上界=红（超前协议同样禁派发）");
        assertTrue(newer.reason() != null && newer.reason().contains("1.5"),
                "红态理由含上界：" + newer.reason());
    }

    @Test
    void compatDiff_manifest空表_全判兼容不炸() {
        insertExecutor("compat-any-node", "0.1.0");
        List<ExecutorCompatView> diff = family(manifestService.compatDiff());
        assertEquals(1, diff.size());
        assertTrue(diff.get(0).compatible(), "未导入发布清单=全兼容（诚实空态不拦人）");
    }

    @Test
    void compatDiff_version空白_旧执行器零漂移记兼容() {
        manifest("1.3.0", "1.5.0");
        insertExecutor("compat-legacy-node", null);
        List<ExecutorCompatView> diff = family(manifestService.compatDiff());
        assertTrue(diff.get(0).compatible(), "版本未上报=旧执行器零漂移放行（缺失不谎判红）");
    }

    @Test
    void compatDiff_本族执行器零行_诚实空态() {
        manifest("1.3.0", "1.5.0");
        assertTrue(family(manifestService.compatDiff()).isEmpty(),
                "本族零行=不产出本族条目（共享台账含他人活体心跳行，全局空列表不可安全观察）");
    }

    @Test
    void compatDiff_区间一侧缺省_该侧不设闸() {
        manifest("1.3.0", null);
        insertExecutor("compat-above-max-free", "9.9.9");
        insertExecutor("compat-below-min", "1.0.0");
        List<ExecutorCompatView> diff = manifestService.compatDiff();
        assertTrue(diff.stream().filter(d -> d.name().equals("compat-above-max-free"))
                .findFirst().orElseThrow().compatible(), "上界缺省=上不设闸");
        assertFalse(diff.stream().filter(d -> d.name().equals("compat-below-min"))
                .findFirst().orElseThrow().compatible(), "下界仍生效");
    }

    // ---- hypothetical-range pre-check ----

    @Test
    void compatDiffAgainst_scoresHypotheticalRange_ignoringStoredManifest() {
        // Stored latest is 9.0~9.9 (everything red); the hypothetical 1.3.0~1.5.0
        // must be scored instead — proves the pre-check never falls back to latest().
        manifest("9.0.0", "9.9.0");
        insertExecutor("compat-ok-node", "1.4.0");
        insertExecutor("compat-old-node", "1.2.0");

        List<ExecutorCompatView> diff = family(manifestService.compatDiffAgainst("1.3.0", "1.5.0"));
        assertEquals(2, diff.size());
        assertTrue(diff.stream().filter(d -> d.name().equals("compat-ok-node"))
                .findFirst().orElseThrow().compatible(), "hypothetical range: in-range node stays green");
        ExecutorCompatView old = diff.stream().filter(d -> d.name().equals("compat-old-node"))
                .findFirst().orElseThrow();
        assertFalse(old.compatible(), "hypothetical range: below-min node is red");
        assertTrue(old.reason() != null && old.reason().contains("1.3"),
                "red reason must quote the hypothetical lower bound: " + old.reason());
    }

    @Test
    void compatDiffAgainst_bothBoundsAbsent_everyNodeCompatible() {
        insertExecutor("compat-free-node", "0.1.0");
        List<ExecutorCompatView> diff = family(manifestService.compatDiffAgainst(null, null));
        assertEquals(1, diff.size());
        assertTrue(diff.get(0).compatible(), "no bounds on either side = nothing gated");
    }

    @Test
    void compatDiffAgainst_oneSideAbsent_thatSideStaysOpen() {
        insertExecutor("compat-above-all", "9.9.9");
        insertExecutor("compat-below-min", "1.0.0");
        List<ExecutorCompatView> diff = manifestService.compatDiffAgainst("1.3.0", null);
        assertTrue(diff.stream().filter(d -> d.name().equals("compat-above-all"))
                .findFirst().orElseThrow().compatible(), "absent upper bound = no ceiling");
        assertFalse(diff.stream().filter(d -> d.name().equals("compat-below-min"))
                .findFirst().orElseThrow().compatible(), "lower bound still enforced");
    }

    @Test
    void compatDiffAgainst_lowerAboveUpper_rejectedWithHumanMessage() {
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.sixbean.testpike.common.exception.ServiceException.class,
                () -> manifestService.compatDiffAgainst("2.0.0", "1.0.0"));
        assertTrue(ex.getMessage().contains("2.0.0") && ex.getMessage().contains("1.0.0"),
                "human message must name both bounds: " + ex.getMessage());
    }
}
