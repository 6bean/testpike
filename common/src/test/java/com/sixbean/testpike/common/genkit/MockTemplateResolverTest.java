package com.sixbean.testpike.common.genkit;

import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mock template kernel unit tests: echo semantics of the request
 * context, restricted JSONPath on one dialect,
 * seed-reproducible random family, cycle rotation, and fail-closed rejections
 * with human-readable messages. The ${gen.*} family must stay resolvable
 * through the single existing parser — no second syntax inside mock templates.
 */
class MockTemplateResolverTest {

    private static MockRequestContext sampleRequest() {
        Map<String, String> pathVars = new LinkedHashMap<>();
        pathVars.put("user", "42");
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("page", List.of("3"));
        query.put("ids", List.of("a", "b", "c", "d", "e", "f"));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-trace-id", "abc-123");
        headers.put("x-tp-mock-session", "lane-7");
        return new MockRequestContext("POST", "/mocks/s1/orders/42", pathVars, query, headers,
                "{\"data\":{\"name\":\"Pike\",\"price\":99.5},\"paid\":true}");
    }

    // ---------- echo semantics ----------

    @Test
    void pathVar_reference_echosWinnerCapture() {
        MockTemplateResolver resolver = new MockTemplateResolver(sampleRequest(), null);
        assertEquals("order-42", resolver.render("order-{{mock.pathVar.user}}"));
    }

    @Test
    void query_reference_echosFirstValue_failsClosedOnMissingKey() {
        MockTemplateResolver resolver = new MockTemplateResolver(sampleRequest(), null);
        assertEquals("p3", resolver.render("p{{mock.query.page}}"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> resolver.render("{{mock.query.nosuch}}"));
        assertTrue(ex.getMessage().contains("nosuch"), "missing key must be named: " + ex.getMessage());
    }

    @Test
    void header_reference_isCaseInsensitiveOnBothEnds() {
        MockTemplateResolver resolver = new MockTemplateResolver(sampleRequest(), null);
        // context stores lowercase, template spells the canonical mixed case
        assertEquals("abc-123", resolver.render("{{mock.header.X-Trace-Id}}"));
        assertEquals("abc-123", resolver.render("{{mock.header.x-trace-id}}"));
    }

    @Test
    void session_reference_echoesTheResolvedLane_failsClosedWithoutOne() {
        MockTemplateResolver resolver = new MockTemplateResolver(sampleRequest(), null);
        assertEquals("journey=lane-7", resolver.render("journey={{mock.session}}"));
        // a request whose lane never got resolved must refuse, not echo empty
        MockRequestContext headless = new MockRequestContext("GET", "/mocks/s1/x",
                Map.of(), Map.of(), Map.of(), null);
        assertThrows(ServiceException.class,
                () -> new MockTemplateResolver(headless, null).render("{{mock.session}}"));
    }

    @Test
    void body_reference_navigatesOnTheRestrictedSubsetSingleSource() {
        MockTemplateResolver resolver = new MockTemplateResolver(sampleRequest(), null);
        assertEquals("Pike", resolver.render("{{mock.body '$.data.name'}}"));
        assertEquals("99.5", resolver.render("{{mock.body '$.data.price'}}"));
        // filter dialect is rejected by NonUiActionContract (no second JSONPath grammar)
        ServiceException ex = assertThrows(ServiceException.class,
                () -> resolver.render("{{mock.body '$.data[?(@.name)]'}}"));
        assertTrue(ex.getMessage().contains("受限子集") || ex.getMessage().contains("subset"),
                "must reuse the single-source rejection: " + ex.getMessage());
    }

    @Test
    void body_reference_failsClosedWhenRequestHasNoBody() {
        MockRequestContext empty = new MockRequestContext("GET", "/mocks/s1/x",
                Map.of(), Map.of(), Map.of(), null);
        MockTemplateResolver resolver = new MockTemplateResolver(empty, null);
        assertThrows(ServiceException.class, () -> resolver.render("{{mock.body '$.a'}}"));
    }

    // ---------- function family ----------

    @Test
    void now_withPatternFormatsCurrentDate_defaultWhenNoArgs() {
        MockTemplateResolver resolver = new MockTemplateResolver(sampleRequest(), null);
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        assertEquals(today, resolver.render("{{mock.now('yyyy-MM-dd')}}"));
        // no-arg form falls back to the GenKit.dateTime default pattern
        assertEquals(19, resolver.render("{{mock.now}}").length());
    }

    @Test
    void randomInt_honoursBounds_andExplicitSeedReproduces() {
        MockTemplateResolver resolver = new MockTemplateResolver(sampleRequest(), null);
        String a = resolver.render("{{mock.randomInt(1,99,seed=7)}}");
        String b = new MockTemplateResolver(sampleRequest(), null)
                .render("{{mock.randomInt(1,99,seed=7)}}");
        assertEquals(a, b, "same seed must replay the same value");
        int v = Integer.parseInt(a);
        assertTrue(v >= 1 && v <= 99, "value " + v + " out of bounds");
    }

    @Test
    void randomInt_withoutSeed_recordsSeedEventForReplay() {
        List<Map<String, Object>> events = new java.util.ArrayList<>();
        MockTemplateResolver resolver = new MockTemplateResolver(sampleRequest(), events);
        resolver.render("{{mock.randomInt(10,20)}}");
        assertEquals(1, events.size());
        assertEquals("randomInt", events.get(0).get("fn"));
        assertTrue(events.get(0).get("seed") instanceof Long, "seed event shape {fn, seed} parity with gen family");
    }

    @Test
    void randomInt_rejectsNonNumericBoundsAndReversedRange() {
        MockTemplateResolver resolver = new MockTemplateResolver(sampleRequest(), null);
        assertThrows(ServiceException.class, () -> resolver.render("{{mock.randomInt(x,9)}}"));
        assertThrows(ServiceException.class, () -> resolver.render("{{mock.randomInt(9,1)}}"));
    }

    @Test
    void cycle_rotatesOverQueryList_oneCursorPerReference() {
        MockTemplateResolver resolver = new MockTemplateResolver(sampleRequest(), null);
        assertEquals("a|b|c|d|e|f|a", resolver.render(
                "{{mock.cycle('ids')}}|{{mock.cycle('ids')}}|{{mock.cycle('ids')}}|"
                        + "{{mock.cycle('ids')}}|{{mock.cycle('ids')}}|{{mock.cycle('ids')}}|"
                        + "{{mock.cycle('ids')}}"));
        // second positional argument is the step (design sample cycle('list-ref',5) style)
        MockTemplateResolver stepped = new MockTemplateResolver(sampleRequest(), null);
        assertEquals("a|c", stepped.render("{{mock.cycle('ids',2)}}|{{mock.cycle('ids',2)}}"));
    }

    @Test
    void cycle_failsClosedOnMissingReference() {
        MockTemplateResolver resolver = new MockTemplateResolver(sampleRequest(), null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> resolver.render("{{mock.cycle('ghost')}}"));
        assertTrue(ex.getMessage().contains("ghost"));
    }

    // ---------- gen passthrough & fail-closed sweep ----------

    @Test
    void genFunctions_stayUsableInsideMockTemplates_singleParserNoNewSyntax() {
        MockTemplateResolver resolver = new MockTemplateResolver(sampleRequest(), null);
        String out = resolver.render("{{mock.pathVar.user}}-${gen.md5(text=hi)}-${gen.dateTime(format=yyyyMMdd)}");
        assertTrue(out.startsWith("42-"), out);
        assertTrue(out.contains("49f68a5c8493ec2c0bf489821c21fc3b"), "gen.md5 of \"hi\": " + out);
        assertTrue(out.matches(".*-\\d{8}$"), "gen.dateTime resolved: " + out);
    }

    @Test
    void unknownMockFunction_failsClosedNamingAvailableOnes() {
        MockTemplateResolver resolver = new MockTemplateResolver(sampleRequest(), null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> resolver.render("{{mock.teleport(1)}}"));
        assertTrue(ex.getMessage().contains("teleport"), ex.getMessage());
        assertTrue(ex.getMessage().contains("randomInt"), "must list the whitelist: " + ex.getMessage());
    }

    @Test
    void malformedMockPlaceholder_neverLeaksRawToken() {
        MockTemplateResolver resolver = new MockTemplateResolver(sampleRequest(), null);
        // unquoted body path / empty key / dangling namespace all fail closed
        assertThrows(ServiceException.class, () -> resolver.render("{{mock.body $.data.name}}"));
        assertThrows(ServiceException.class, () -> resolver.render("{{mock.query.}}"));
        assertThrows(ServiceException.class, () -> resolver.render("{{mock.pathVar}}"));
    }

    @Test
    void plainText_isIdentityAndNullPassesThrough() {
        MockTemplateResolver resolver = new MockTemplateResolver(sampleRequest(), null);
        assertEquals("no placeholder here", resolver.render("no placeholder here"));
        assertEquals("{\"a\":1}", resolver.render("{\"a\":1}"));
        assertEquals(null, resolver.render(null));
    }

    @Test
    void mixedTemplate_resolvesBothNamespacesTogether() {
        List<Map<String, Object>> events = new java.util.ArrayList<>();
        MockTemplateResolver resolver = new MockTemplateResolver(sampleRequest(), events);
        String out = resolver.render(
                "{\"id\":\"{{mock.pathVar.user}}\",\"name\":\"{{mock.body '$.data.name'}}\"}");
        assertEquals("{\"id\":\"42\",\"name\":\"Pike\"}", out);
        assertNotEquals(-1, out.indexOf("42"));
    }
}
