package com.sixbean.testpike.core.perf.domain.component;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Residual-cleanup guide tests (GB-4, ): the run report turns the launch-pinned
 * isolation declaration into a per-mode cleanup guide for the team. The platform
 * owns no probe into the system-under-test's data plane, so every guide is an
 * instruction sheet (what to check on the SUT side after the press), never a
 * fabricated scan result. The mode truth is the pinned snapshot only — a missing
 * or corrupt snapshot yields the honest absence (null), never a guessed mode.
 */
class PerfResidualGuideTest {

    private final PerfResidualGuide guide = new PerfResidualGuide();

    /** HEADER_MARK runs: the guide names the single station-wide mark header and
     *  asks for a trace search on the SUT side (sampling verification, count and
     *  spot-check), with the boundary note that only the SUT side holds the data. */
    @Test
    void headerMarkModeYieldsSamplingVerificationGuide() {
        Map<String, Object> out = guide.build("HEADER_MARK", null);

        assertThat(out).isNotNull();
        assertThat(out.get("mode")).isEqualTo("HEADER_MARK");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) out.get("sections");
        assertThat(sections).isNotEmpty();
        String text = String.valueOf(sections);
        assertThat(text).contains("X-Load-Test");
        assertThat(out.get("boundaryNote")).isNotNull();
    }

    /** NS_ACCOUNT run bound to a dataset whose columns carry account-shaped
     *  names: the recycle list names the dataset, the account columns found and
     *  the row count so the operator knows exactly which pool to retire. */
    @Test
    void nsAccountModeWithBoundDatasetNamesAccountColumnsAndRowCount() {
        Map<String, Object> dataset = Map.of(
                "name", "账号池A",
                "columns_json", "[\"username\",\"password\",\"userId\"]",
                "row_count", 300);

        Map<String, Object> out = guide.build("NS_ACCOUNT", dataset);

        assertThat(out).isNotNull();
        assertThat(out.get("mode")).isEqualTo("NS_ACCOUNT");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) out.get("sections");
        String text = String.valueOf(sections);
        assertThat(text).contains("账号池A");
        assertThat(text).contains("username");
        assertThat(text).contains("300");
    }

    /** NS_ACCOUNT run with no dataset bound at launch (or the ledger row since
     *  deleted): the guide honestly degrades — it still says an account recycle
     *  pass is due, but names that no pool was pinned so no list can be drawn. */
    @Test
    void nsAccountModeWithoutDatasetDegradesHonestly() {
        Map<String, Object> out = guide.build("NS_ACCOUNT", null);

        assertThat(out).isNotNull();
        assertThat(out.get("mode")).isEqualTo("NS_ACCOUNT");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) out.get("sections");
        String text = String.valueOf(sections);
        assertThat(text).contains("未绑定");
    }

    /** SHADOW_TABLE runs: the guide is a shadow-table counting instruction sheet;
     *  the boundary note states the data-plane isolation lives on the SUT side —
     *  the platform never claims it counted the shadow tables itself. */
    @Test
    void shadowTableModeYieldsCountingGuideWithHonestBoundary() {
        Map<String, Object> out = guide.build("SHADOW_TABLE", null);

        assertThat(out).isNotNull();
        assertThat(out.get("mode")).isEqualTo("SHADOW_TABLE");
        assertThat(String.valueOf(out.get("sections"))).contains("影子表");
        assertThat(String.valueOf(out.get("boundaryNote"))).contains("被测侧");
    }

    /** Not declared at launch (null isolationMode): no guide is attached — the
     *  honest absence, never a default-mode guide invented after the fact. */
    @Test
    void undeclaredIsolationYieldsHonestAbsence() {
        assertThat(guide.build(null, null)).isNull();
        assertThat(guide.build("", null)).isNull();
    }

    /** A mode spelling outside the single-source roster (legacy row / tampered
     *  snapshot): no guide is invented — the roster decides, never a prefix match
     *  or a closest-guess branch. */
    @Test
    void unknownModeSpellingOutsideTheRosterYieldsNoGuide() {
        Map<String, Object> out = guide.build("MAGIC_MODE", null);

        assertThat(out).isNull();
    }
}
