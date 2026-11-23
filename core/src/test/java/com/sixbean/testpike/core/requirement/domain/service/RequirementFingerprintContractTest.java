package com.sixbean.testpike.core.requirement.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.requirement.api.dto.RequirementInfo;
import com.sixbean.testpike.core.requirement.api.dto.RequirementQuery;
import com.sixbean.testpike.core.support.RequirementDedupFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contracts for the stable content fingerprint on tp_requirement ( ID and
 * fingerprint item: both requirement lists must expose a replay-stable short
 * fingerprint that never drifts for identical content and never reuses the
 * id). The fingerprint is defined exactly once, as the IMMUTABLE SQL function
 * requirement_fingerprint(doc_key, body); the table column is a GENERATED
 * ALWAYS ... STORED projection of it, so application code can never write a
 * second, drifting implementation (R40 single source). Covers:
 * 1) function determinism — case/whitespace-insensitive equality, document
 * separation, XXXX-XXXX display format;
 * 2) generated column landing — value present on read, normalization-stable
 * across cosmetic edits, changes with real content, identical on replay;
 * 3) attribution order — the anchored documentId wins over source_id, pinning
 * the preview/management cross-page equality of the same extracted item;
 * 4) DTO mirror — core RequirementInfo carries the column through the list
 * and detail reads that serve the management page.
 * Runs against the real dev database — no mocks; fixture seeding and
 * id-anchored cleanup follow the shared RequirementDedupFixture discipline
 * (factory tables are never TRUNCATED).
 */
@SpringBootTest(classes = CoreApplication.class)
class RequirementFingerprintContractTest {

    private static final Pattern DISPLAY_FORMAT = Pattern.compile("^[0-9A-F]{4}-[0-9A-F]{4}$");

    private final List<Long> createdIds = new java.util.ArrayList<>();
    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;
    @Autowired
    private ApplicationContext appContext;
    @Autowired
    private RequirementService requirementService;
    private RequirementDedupFixture dedupFixture;
    private long projectId;

    @BeforeEach
    void seedProject() {
        dedupFixture = RequirementDedupFixture.fromContext(appContext, "rfs", "指纹契约项目");
        projectId = dedupFixture.projectA();
    }

    @AfterEach
    void cleanupRows() {
        for (Long id : createdIds) {
            jdbcTemplate.update("DELETE FROM tp_requirement WHERE id = :id",
                    new MapSqlParameterSource("id", id));
        }
        createdIds.clear();
        dedupFixture.cleanup();
    }

    /** Case 1: pure-function determinism and display format. */
    @Test
    void functionIsCaseAndWhitespaceInsensitiveYetDocumentSeparating() {
        String a = fingerprint("D1", "Foo   BAR");
        String b = fingerprint("d1", "  foo bar ");
        String otherDoc = fingerprint("D2", "Foo BAR");
        assertEquals(a, b, "normalization (lower + whitespace fold) must not split equal content");
        assertNotEquals(a, otherDoc, "different document attribution must yield a different fingerprint");
        assertTrue(DISPLAY_FORMAT.matcher(a).matches(),
                "fingerprint must render as the readable XXXX-XXXX group, got: " + a);
    }

    /** Case 2: the stored generated column lands, survives cosmetic edits and replays identically. */
    @Test
    void generatedColumnLandsNormalizesAndReplaysStably() {
        MapSqlParameterSource v1 = insertParams("  登录  超时   后  锁定 ", "AC   Alpha", "424242", null);
        String stored = insertAndTrackReturningFingerprint(v1);
        assertTrue(DISPLAY_FORMAT.matcher(stored).matches());

        // Cosmetic-only edit (trim + whitespace runs fold to one space): frozen fingerprint.
        jdbcTemplate.update("UPDATE tp_requirement SET title = '登录 超时 后 锁定' WHERE id = :id",
                new MapSqlParameterSource("id", lastId));
        assertEquals(stored, fingerprintOfLastRow(), "cosmetic whitespace change must not drift the fingerprint");

        // Real content change: fingerprint moves.
        jdbcTemplate.update("UPDATE tp_requirement SET acceptance_criteria = 'AC Beta' WHERE id = :id",
                new MapSqlParameterSource("id", lastId));
        assertNotEquals(stored, fingerprintOfLastRow(), "content change must be reflected by the fingerprint");

        // Replay: identical doc + content adopted again yields the identical fingerprint.
        String replayed = insertAndTrackReturningFingerprint(
                insertParams("  登录  超时  后  锁定 ", "AC Alpha", "424242", null));
        String firstNormalized = normalizeCompare(stored, replayed);
        assertEquals(firstNormalized, "SAME",
                "re-adopting identical content from the same document must replay the same fingerprint");
    }

    /** Case 3: anchored documentId wins over source_id so preview and management pages agree. */
    @Test
    void columnExpressionPrefersAnchoredDocumentIdOverSourceId() {
        MapSqlParameterSource p = insertParams("指纹归属列", "按锚文档", "4242", "JIRA-9");
        String stored = insertAndTrackReturningFingerprint(p);
        String viaAnchor = fingerprint("4242", "指纹归属列" + "\n" + "按锚文档");
        String viaSourceId = fingerprint("JIRA-9", "指纹归属列" + "\n" + "按锚文档");
        assertEquals(normalizeCompare(stored, viaAnchor), "SAME",
                "column must hash coalesce(source_anchor.documentId, source_id) — documentId first");
        assertNotEquals(viaAnchor, viaSourceId, "test must be able to tell the two attribution orders apart");
    }

    /** Case 4: the DTO that serves the management list/detail carries the fingerprint. */
    @Test
    void requirementInfoDtoMirrorsFingerprintColumn() {
        String stored = insertAndTrackReturningFingerprint(insertParams("DTO 镜像", null, "777", null));
        RequirementInfo info = requirementService.get(lastId);
        assertNotNull(info.fingerprint(), "RequirementInfo must expose the generated fingerprint column");
        assertEquals(stored, info.fingerprint());
    }

    /** Case 5 : the list query filters by exact content fingerprint.
 * Normalized input (lowercase / 8-hex compact) hits the same row; an unknown
 * but well-formed value and a malformed value both honestly return zero rows
 * (no exception — a fingerprint that cannot exist matches nothing); blank
 * input keeps the predicate off entirely. */
    @Test
    void listFiltersByExactFingerprintNormalizingInput() {
        String fpA = insertAndTrackReturningFingerprint(insertParams("筛选甲", "AC-A", "9001", null));
        long rowA = lastId;
        String fpB = insertAndTrackReturningFingerprint(insertParams("筛选乙", "AC-B", "9002", null));
        long rowB = lastId;
        assertNotEquals(fpA, fpB, "fixture must seed two distinct fingerprints");

        var hitA = requirementService.list(new RequirementQuery(
                projectId, null, null, 1, 50, null, null, null, fpA));
        assertEquals(1, hitA.total());
        assertEquals(rowA, hitA.list().get(0).id());
        assertEquals(fpA, hitA.list().get(0).fingerprint());

        var lower = requirementService.list(new RequirementQuery(
                projectId, null, null, 1, 50, null, null, null, fpA.toLowerCase()));
        assertEquals(1, lower.total(), "lowercase input must normalize to the stored uppercase form");

        var noDash = requirementService.list(new RequirementQuery(
                projectId, null, null, 1, 50, null, null, null, fpA.replace("-", "")));
        assertEquals(1, noDash.total(), "8-hex compact input must normalize to XXXX-XXXX");
        assertEquals(rowA, noDash.list().get(0).id());

        var hitB = requirementService.list(new RequirementQuery(
                projectId, null, null, 1, 50, null, null, null, fpB));
        assertEquals(1, hitB.total(), "each seeded row must be reachable by its own fingerprint");
        assertEquals(rowB, hitB.list().get(0).id());

        assertEquals(0, requirementService.list(new RequirementQuery(
                projectId, null, null, 1, 50, null, null, null, "0000-0000")).total(),
                "well-formed unknown fingerprint = zero rows, no exception");
        assertEquals(0, requirementService.list(new RequirementQuery(
                projectId, null, null, 1, 50, null, null, null, "not-a-fingerprint")).total(),
                "malformed fingerprint input = zero rows, no exception");

        assertTrue(requirementService.list(new RequirementQuery(
                projectId, null, null, 1, 50, null, null, null, "   ")).total() >= 2,
                "blank = no fingerprint predicate");
    }

    // ------------------------------------------------------------------
    // helpers — all fingerprint values in this test come from the single
    // SQL function, never a Java re-implementation.
    // ------------------------------------------------------------------

    private long lastId;

    private String fingerprint(String docKey, String body) {
        return jdbcTemplate.getJdbcTemplate().queryForObject(
                "SELECT requirement_fingerprint(?, ?)", String.class, docKey, body);
    }

    private MapSqlParameterSource insertParams(String title, String criteria, String docId, String sourceId) {
        return new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("title", title)
                .addValue("criteria", criteria)
                .addValue("anchor", docId == null ? null : "{\"documentId\":\"" + docId + "\"}")
                .addValue("sourceId", sourceId);
    }

    private String insertAndTrackReturningFingerprint(MapSqlParameterSource p) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_requirement(project_id, source, source_id, title, acceptance_criteria,"
                        + " chapter, source_anchor)"
                        + " VALUES (:projectId, 2, :sourceId, :title, :criteria, 'fp/contract',"
                        + " CAST(:anchor AS JSONB)) RETURNING id",
                p, Long.class);
        assertNotNull(id);
        lastId = id;
        createdIds.add(id);
        String fp = fingerprintOfRow(id);
        assertNotNull(fp, "generated column must land a fingerprint on every inserted row");
        return fp;
    }

    private String fingerprintOfLastRow() {
        return fingerprintOfRow(lastId);
    }

    private String fingerprintOfRow(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT fingerprint FROM tp_requirement WHERE id = :id",
                new MapSqlParameterSource("id", id), String.class);
    }

    /** Returns SAME when the two XXXX-XXXX values are equal, otherwise a diff-able label. */
    private String normalizeCompare(String left, String right) {
        return java.util.Objects.equals(left, right) ? "SAME" : left + "!=" + right;
    }
}
