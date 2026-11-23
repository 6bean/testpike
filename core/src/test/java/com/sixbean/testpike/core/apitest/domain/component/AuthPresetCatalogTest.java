package com.sixbean.testpike.core.apitest.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.apitest.api.dto.AuthPresetInfo;
import com.sixbean.testpike.core.config.api.dto.ConfigInfo;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signature auth preset catalogue reads (W1c segment C-2a).
 * <p>The catalogue is the single read path for the editor dropdown: prefix filter
 * the whole catalogue), template text served verbatim for the downstream single
 * resolvers (executor ${gen.*} parser + dispatch {{CRED:}} injector — the catalogue
 * evaluates nothing). Config rows come from an in-memory stub (L2 infrastructure
 * isolation); seeded rows and the endpoints are exercised at L3.
 */
class AuthPresetCatalogTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String HMAC_VALUE =
            "{\"label\":\"HMAC-SHA256 签名\",\"assemblies\":["
                    + "{\"into\":\"header\",\"name\":\"X-Timestamp\",\"value\":\"${gen.timestamp(unit=s)}\"},"
                    + "{\"into\":\"header\",\"name\":\"X-Sign\","
                    + "\"value\":\"${gen.hmacSha256(text=abc,key={{CRED:sk}})}\"}]}";

    private static ConfigInfo row(String key, String value) {
        return new ConfigInfo(1L, key, value, AuthPresetCatalog.PRESET_GROUP,
                "json", 0, "test row");
    }

    private static AuthPresetCatalog catalog(ConfigInfo... rows) {
        return new AuthPresetCatalog(new GroupRowsConfigServiceStub(List.of(rows)), MAPPER);
    }

    @Test
    void presetsUnderPrefixParseIntoEditorShapes() {
        List<AuthPresetInfo> presets = catalog(row("apitest.auth.preset.hmac-sha256",
                HMAC_VALUE)).presets();
        assertEquals(1, presets.size());
        AuthPresetInfo hmac = presets.get(0);
        assertEquals("hmac-sha256", hmac.presetKey(), "identifier = key suffix below the prefix");
        assertEquals("HMAC-SHA256 签名", hmac.label());
        assertEquals(2, hmac.assemblies().size());
        assertEquals("header", hmac.assemblies().get(0).into());
        assertEquals("X-Timestamp", hmac.assemblies().get(0).name());
        assertEquals("${gen.timestamp(unit=s)}", hmac.assemblies().get(0).value(),
                "template text must pass through untouched — no second resolver upstream");
    }

    @Test
    void keysOutsideThePrefixStayOutOfTheCatalogue() {
        List<AuthPresetInfo> presets = catalog(
                row("apitest.auth.preset.hmac-sha256", HMAC_VALUE),
                row("exec.http.timeout-seconds", "10"),
                row("some.other.preset.x", HMAC_VALUE)).presets();
        assertEquals(1, presets.size(), "only prefix rows are served");
    }

    @Test
    void brokenRowDropsHonestlyOthersSurvive() {
        List<AuthPresetInfo> presets = catalog(
                row("apitest.auth.preset.md5", "{\"label\":\"MD5\",\"assemblies\":[]}"),
                row("apitest.auth.preset.hmac-sha256", HMAC_VALUE),
                row("apitest.auth.preset.sha1", "{not json")).presets();
        assertEquals(1, presets.size(),
                "an empty-assembly row and a non-JSON row must drop; the valid one stays visible");
        assertEquals("hmac-sha256", presets.get(0).presetKey());
        assertTrue(presets.get(0).assemblies().get(1).value().contains("{{CRED:sk}}"),
                "credential references keep their reference form — resolution is downstream");
    }

    @Test
    void rowWithoutLabelOrWithIncompleteAssemblyRowsDrops() {
        List<AuthPresetInfo> presets = catalog(
                row("apitest.auth.preset.noLabel",
                        "{\"assemblies\":[{\"name\":\"X\",\"value\":\"${gen.md5(text=a)}\"}]}"),
                row("apitest.auth.preset.noName",
                        "{\"label\":\"缺名\",\"assemblies\":[{\"value\":\"v\"}]}"),
                row("apitest.auth.preset.badInto",
                        "{\"label\":\"坏位\",\"assemblies\":[{\"into\":\"body\",\"name\":\"X\",\"value\":\"v\"}]}"),
                row("apitest.auth.preset.hmac-sha256", HMAC_VALUE)).presets();
        assertEquals(1, presets.size(), "unusable presets must not reach the editor dropdown");
    }

    @Test
    void seededMigrationRowsStayCanonicalAndPassTheSaveGate() throws Exception {
        // The shipped seed is the user's first experience of the catalogue: the
        // templates must use the executor's canonical ${gen.*} dialect (docs/04
        // rule 6a, single parser) and pass the very save gate a hand-written
        // signature request faces — a preset the editor pre-fills must never be
        // rejected when the user saves it.
        // surefire may run from the module dir — resolve from the repository root
        Path repoRoot = Path.of(System.getProperty("user.dir"));
        while (repoRoot != null && !Files.isDirectory(repoRoot.resolve("db/migration"))) {
            repoRoot = repoRoot.getParent();
        }
        assertTrue(repoRoot != null, "repository root with db/migration must be reachable");
        String sql = Files.readString(
                repoRoot.resolve("db/migration/V257__apitest_auth_preset_seed.sql"),
                StandardCharsets.UTF_8);
        List<String> payloads = extractConcatenatedLiterals(sql);
        assertEquals(3, payloads.size(), "three preset rows expected in the seed");
        for (String json : payloads) {
            JsonNode value = MAPPER.readTree(json);
            assertTrue(json.contains("${gen."), "canonical dialect only: " + json);
            assertTrue(!json.contains("{{gen."), "no {{gen. leftover: " + json);
            JsonNode auth = MAPPER.readTree("{\"type\":\"signature\",\"assemblies\":"
                    + value.path("assemblies").toString() + "}");
            assertNull(AuthFormContract.violation(auth),
                    "seed assembly rows must pass the request save gate");
        }
    }

    /**
     * Merge SQL single-quoted literals concatenated with || (the seed builds JSON
     * values across several fragments); keep only fragments that start a JSON
     * object so descriptions and key literals are ignored.
     */
    private static List<String> extractConcatenatedLiterals(String sql) {
        List<String> out = new ArrayList<>();
        int cursor = 0;
        while (true) {
            int start = sql.indexOf("'{\"", cursor);
            if (start < 0) {
                break;
            }
            StringBuilder merged = new StringBuilder();
            int pos = start + 1;
            while (true) {
                int end = sql.indexOf("'", pos);
                assertTrue(end >= 0, "unterminated SQL literal in the seed file");
                merged.append(sql, pos, end);
                // next non-space token after the closing quote decides: || continues
                // the concatenation, anything else ends this JSON value.
                int probe = end + 1;
                while (probe < sql.length() && Character.isWhitespace(sql.charAt(probe))) {
                    probe++;
                }
                if (sql.startsWith("||", probe)) {
                    pos = sql.indexOf("'", probe) + 1;
                    continue;
                }
                break;
            }
            out.add(merged.toString());
            cursor = pos;
        }
        return out;
    }
}
