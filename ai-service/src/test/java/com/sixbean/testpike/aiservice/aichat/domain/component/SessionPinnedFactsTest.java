package com.sixbean.testpike.aiservice.aichat.domain.component;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * a2 pinned-context renderer contract: a session's whitelisted
 * navigation context (written at create time by core's context door) becomes
 * fact lines for the assembly's pinned section. Pins the defence-in-depth
 * shape — fixed whitelist order so the prompt prefix stays byte-stable, keys
 * outside the identifier set and non-scalar values never render, and every
 * broken or absent payload renders zero facts instead of throwing at a turn.
 */
class SessionPinnedFactsTest {

    @Test
    void whitelistedScalarsRenderInFixedOrderAndForeignKeysAreDropped() {
        List<String> facts = SessionPinnedFacts.render(
                "{\"executionId\":12,\"password\":\"s3cret\",\"route\":\"/executions\","
                        + "\"projectId\":3,\"objectId\":\"c-9\"}");

        // order is the whitelist order, never the payload's key order —
        // provider prompt caching wants a byte-stable prefix. Since the
        // route line is the D4 waterline sentence instead of a bare key line.
        assertEquals(List.of("本问发起页面 = /executions", "projectId: 3",
                "objectId: c-9", "executionId: 12"), facts);
    }

    @Test
    void waterlineSentenceNamesBothTheAskedFromPageAndTheSessionOrigin() {
        List<String> facts = SessionPinnedFacts.render(
                "{\"route\":\"/cases\",\"origin_route\":\"/dashboard\","
                        + "\"projectId\":3,\"password\":\"s3cret\"}");

        assertEquals(List.of("本问发起页面 = /cases（会话建于 /dashboard）", "projectId: 3"), facts,
                "route and origin_route fold into one D4 waterline sentence — "
                        + "neither may also render as a bare key line");
    }

    @Test
    void sessionOriginOnlyNeverFakesAnAskedFromPage() {
        List<String> facts = SessionPinnedFacts.render(
                "{\"origin_route\":\"/dashboard\",\"projectId\":3}");

        assertEquals(List.of("会话建于: /dashboard", "projectId: 3"), facts,
                "without a per-question route the origin falls back to a plain "
                        + "fact line instead of a half-true waterline sentence");
    }

    @Test
    void analysisAndDocumentAnchorsAreWhitelistedNotSmuggledOut() {
        List<String> facts = SessionPinnedFacts.render(
                "{\"documentId\":8639,\"analysisId\":412,\"route\":\"/docs\","
                        + "\"password\":\"s3cret\"}");

        // the render vocabulary is core's door set plus the
        // server-folded origin_route, from one shared source — a key the
        // write door accepts must never read back as noise on this side.
        assertEquals(List.of("本问发起页面 = /docs", "analysisId: 412", "documentId: 8639"), facts);
    }

    @Test
    void nestedOrBlankValuesAreIgnoredNotSmuggled() {
        List<String> facts = SessionPinnedFacts.render(
                "{\"route\":\"\",\"projectId\":{\"nested\":1},\"objectId\":[1,2],"
                        + "\"executionId\":7}");

        assertEquals(List.of("executionId: 7"), facts,
                "blank text and container values carry no fact line");
    }

    @Test
    void absentBlankOrUnparseablePayloadRendersNoFactsAndNeverThrows() {
        assertTrue(SessionPinnedFacts.render(null).isEmpty());
        assertTrue(SessionPinnedFacts.render("  ").isEmpty());
        assertTrue(SessionPinnedFacts.render("{}").isEmpty());
        assertTrue(SessionPinnedFacts.render("not json").isEmpty(),
                "a broken payload must fail soft to no-facts, never at the turn");
        assertTrue(SessionPinnedFacts.render("[1,2]").isEmpty(),
                "a non-object payload is not a context");
    }
}
