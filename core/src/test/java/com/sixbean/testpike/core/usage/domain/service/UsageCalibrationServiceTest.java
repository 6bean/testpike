package com.sixbean.testpike.core.usage.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.usage.api.dto.UsageSampleInfo;
import com.sixbean.testpike.core.usage.api.dto.UsageSampleInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the usage-sample detail page sorting (real dev
 * database, no mocks per the strict-testing iron law).
 *
 * <p>Fixtures are three samples sharing one unique model marker written through
 * the real {@code record} entry point, so the queried segment contains exactly
 * our rows and every ORDER BY assertion is non-vacuous. The dev table keeps 31+
 * real samples, so cleanup deletes only the marker row set (never TRUNCATE).
 */
@SpringBootTest(classes = CoreApplication.class)
class UsageCalibrationServiceTest {

    @Autowired
    private UsageCalibrationService usageCalibrationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Unique model value isolating this test's segment from real dev samples. */
    private final String marker = "t292b5-sort-" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanFixture() {
        jdbcTemplate.update("DELETE FROM tp_llm_usage_sample WHERE model = ?", marker);
    }

    private void record(int estTokens, String chain, String provider, long latencyMs) {
        usageCalibrationService.record(new UsageSampleInput(
                1L, provider, marker, chain, 1L, estTokens, 100, 50, latencyMs));
    }

    /** E9 read surface: the detail window exposes cachedTokens in both states
     * (a cache hit shows its number, a compat write shows the honest 0), so
     * the calibration page can settle the "no cache" vs "cache hit" reading
     * without a second observation channel. */
    @Test
    void detailPageExposesCachedTokensInBothStates() {
        usageCalibrationService.record(new UsageSampleInput(
                1L, "pv-hit", marker, "extract", 1L, 200, 100, 50, 40, "jtokkit-cl100k", 768));
        usageCalibrationService.record(new UsageSampleInput(
                1L, "pv-zero", marker, "extract", 1L, 200, 100, 50, 40, "jtokkit-cl100k"));
        List<Integer> cached = usageCalibrationService
                .detailPage(marker, null, null, 1, 20).list().stream()
                .map(UsageSampleInfo::cachedTokens)
                .sorted()
                .toList();
        assertEquals(List.of(0, 768), cached);
    }

    /** AP-4 the cache-hit count written through record lands on the
 * real column, and a pre- arg call keeps the honest 0 (no drift). */
    @Test
    void cachedTokensPersistThroughRecordAndCompatConstructor() {
        usageCalibrationService.record(new UsageSampleInput(
                1L, "pv-cache", marker, "extract", 1L, 200, 100, 50, 40, "jtokkit-cl100k", 768));
        usageCalibrationService.record(new UsageSampleInput(
                1L, "pv-compat", marker, "extract", 1L, 200, 100, 50, 40, "jtokkit-cl100k"));
        List<Integer> cached = jdbcTemplate.queryForList(
                "SELECT cached_tokens FROM tp_llm_usage_sample WHERE model = ?"
                        + " AND provider IN ('pv-cache','pv-compat') ORDER BY id",
                Integer.class, marker);
        assertEquals(List.of(768, 0), cached,
                "real cache value must persist; compat constructor must pin 0");
    }

    /** Seed insertion order (r1 est=300, r2 est=100, r3 est=200) deliberately
     * disagrees with both est_tokens orders; the equal "extract" chain pair
     * (r1/r3) pins the id DESC tie-break. */
    private void seedSortFixture() {
        record(300, "extract", "pv-a", 30);
        record(100, "summary", "pv-b", 10);
        record(200, "extract", "pv-c", 20);
    }

    private List<Integer> estTokensOf(int page, int size, String sortBy, String sortDir) {
        PageResult<UsageSampleInfo> result =
                usageCalibrationService.detailPage(marker, null, null, page, size, sortBy, sortDir);
        return result.list().stream().map(UsageSampleInfo::estTokens).toList();
    }

    /** No sort key keeps the historical id DESC order, and the legacy
     * five-argument call delegates to the same default (zero drift). */
    @Test
    void detailSortAbsent_keepsHistoricalIdDescAndLegacyOverloadDelegates() {
        seedSortFixture();
        // id DESC = newest first: r3(est=200), r2(est=100), r1(est=300)
        List<Integer> expected = List.of(200, 100, 300);
        assertEquals(expected, estTokensOf(1, 20, null, null));
        assertEquals(expected, estTokensOf(1, 20, "", ""));
        List<Integer> viaLegacy = usageCalibrationService
                .detailPage(marker, null, null, 1, 20).list().stream()
                .map(UsageSampleInfo::estTokens).toList();
        assertEquals(expected, viaLegacy, "legacy 5-arg overload must not drift");
    }

    /** est_tokens asc/desc mirror each other over the whole marker window. */
    @Test
    void detailSortEstTokens_bothDirections() {
        seedSortFixture();
        assertEquals(List.of(100, 200, 300), estTokensOf(1, 20, "est_tokens", "asc"));
        assertEquals(List.of(300, 200, 100), estTokensOf(1, 20, "est_tokens", "desc"));
    }

    /** chain asc groups the equal "extract" pair with the constant id DESC
     * tie-break, so paging stays deterministic under ties regardless of
     * direction (r3 before r1 inside the group). */
    @Test
    void detailSortChain_tiePinnedByIdDesc() {
        seedSortFixture();
        assertEquals(List.of(200, 300, 100), estTokensOf(1, 20, "chain", "asc"));
        // desc puts the unique "summary" r2 first, then the extract pair tied
        // on id DESC (r3=200 before r1=300)
        assertEquals(List.of(100, 200, 300), estTokensOf(1, 20, "chain", "desc"));
    }

    /** Sorting keeps paging consistent: page 2 continues the order, total is
     * truthful. */
    @Test
    void detailSortAppliesAcrossPages() {
        seedSortFixture();
        PageResult<UsageSampleInfo> first =
                usageCalibrationService.detailPage(marker, null, null, 1, 2, "est_tokens", "asc");
        List<Integer> page1 = first.list().stream().map(UsageSampleInfo::estTokens).toList();
        List<Integer> page2 = estTokensOf(2, 2, "est_tokens", "asc");
        assertEquals(List.of(100, 200), page1);
        assertEquals(List.of(300), page2);
        assertEquals(3L, first.total());
    }

    /** An injection-shaped or off-list key is refused fail-closed with a human
     * 400 naming the whitelist; the fixture rows stay intact afterwards. */
    @Test
    void detailSortOffListKey_refusedWithWhitelistHint() {
        seedSortFixture();
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> estTokensOf(1, 20, "est_tokens; DROP TABLE tp_llm_usage_sample", "asc"));
        assertEquals(40001, rejected.getCode());
        assertTrue(rejected.getMessage().contains("created_at"),
                "error should name the allowed columns, actual=" + rejected.getMessage());
        assertEquals(3, estTokensOf(1, 20, null, null).size(), "rows must be unaffected");
    }
}
