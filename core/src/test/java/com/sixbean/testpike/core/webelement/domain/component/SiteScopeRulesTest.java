package com.sixbean.testpike.core.webelement.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Segment-aligned site scope rules . The scope of a profile is
 * derived from the start URL only on the server side; containment is decided
 * per whole path segment so a /pr/ scope can never swallow /print/, a foreign
 * origin is always out of scope, and same-host links under a different top
 * segment are reported as sibling segments (counted, never queued - the two
 * profiles on one host stay separate by design).
 */
class SiteScopeRulesTest {

    private static final String HOST = "https://xx.com";

    @Test
    void deriveScopeAlignsSegmentsAndKeepsDeepPrefixes() {
        assertEquals("/pr/", SiteScopeRules.deriveScope("https://xx.com/pr"));
        assertEquals("/pr/", SiteScopeRules.deriveScope("https://xx.com/pr/"));
        assertEquals("/pr/deep/", SiteScopeRules.deriveScope("https://xx.com/pr/deep/"));
        assertEquals("/", SiteScopeRules.deriveScope("https://xx.com"));
        assertEquals("/", SiteScopeRules.deriveScope("https://xx.com/"));
        assertEquals("/pr/", SiteScopeRules.deriveScope("https://xx.com/pr?a=1&b=2"));
    }

    @Test
    void deriveScopeRefusesNonHttpOrUnparsableInputs() {
        ServiceException blank = assertThrows(ServiceException.class,
                () -> SiteScopeRules.deriveScope("  "));
        assertTrue(blank.getMessage().contains("起始地址"), blank.getMessage());

        assertThrows(ServiceException.class,
                () -> SiteScopeRules.deriveScope("not a url"));
        assertThrows(ServiceException.class,
                () -> SiteScopeRules.deriveScope("ftp://xx.com/pr/"));
    }

    @Test
    void segmentBoundaryNeverSwallowsSiblings() {
        assertTrue(SiteScopeRules.inScope(HOST, "/pr/", "https://xx.com/pr"));
        assertTrue(SiteScopeRules.inScope(HOST, "/pr/", "https://xx.com/pr/"));
        assertTrue(SiteScopeRules.inScope(HOST, "/pr/", "https://xx.com/pr/orders?id=7"));
        // The core user-facing rule: /pr/ must never match /print/.
        assertFalse(SiteScopeRules.inScope(HOST, "/pr/", "https://xx.com/print/1"));
        assertFalse(SiteScopeRules.inScope(HOST, "/pr/", "https://xx.com/prv/x"));
        assertFalse(SiteScopeRules.inScope(HOST, "/pr/", "https://xx.com/md/1"));
        // Deeper prefixes still align per segment.
        assertTrue(SiteScopeRules.inScope(HOST, "/pr/deep/", "https://xx.com/pr/deep/a/b"));
        assertFalse(SiteScopeRules.inScope(HOST, "/pr/deep/", "https://xx.com/pr/deeper/a"));
    }

    @Test
    void rootScopeCoversWholeHostButNothingElse() {
        assertTrue(SiteScopeRules.inScope(HOST, "/", "https://xx.com/anything/at/all"));
        assertTrue(SiteScopeRules.inScope(HOST, "/", "https://xx.com/"));
        assertFalse(SiteScopeRules.inScope(HOST, "/", "https://other.com/x"));
    }

    @Test
    void foreignOriginIsAlwaysOutOfScopeIncludingSchemeAndPort() {
        assertFalse(SiteScopeRules.inScope(HOST, "/pr/", "http://xx.com/pr/1"));
        assertFalse(SiteScopeRules.inScope(HOST, "/pr/", "https://yy.com/pr/1"));
        assertFalse(SiteScopeRules.inScope(HOST, "/pr/", "https://xx.com:8443/pr/1"));
        // The default port of the scheme is the same origin, not a foreign one.
        assertTrue(SiteScopeRules.inScope(HOST, "/pr/", "https://xx.com:443/pr/1"));
    }

    @Test
    void siblingSegmentNamesTheTopSegmentOnlyForSameHostForeignScopes() {
        assertEquals("md", SiteScopeRules.siblingSegment(HOST, "/pr/", "https://xx.com/md/doc/1"));
        assertEquals("md", SiteScopeRules.siblingSegment(HOST, "/pr/deep/", "https://xx.com/md"));
        // Any other top-level path segment on one host is a sibling segment
        // (counted, never queued), including ones sharing letters with the scope.
        assertEquals("preview", SiteScopeRules.siblingSegment(HOST, "/pr/", "https://xx.com/preview/x"));
        assertNull(SiteScopeRules.siblingSegment(HOST, "/pr/", "https://xx.com/pr/orders"));
        // A root scope covers the whole host, so nothing can be a sibling.
        assertNull(SiteScopeRules.siblingSegment(HOST, "/", "https://xx.com/any"));
        // Foreign origins have no sibling segment: the whole link is out of scope.
        assertNull(SiteScopeRules.siblingSegment(HOST, "/pr/", "https://other.com/md/x"));
        // Unparsable links are not siblings either - callers count them elsewhere.
        assertNull(SiteScopeRules.siblingSegment(HOST, "/pr/", "mailto:someone@xx.com"));
    }
}
