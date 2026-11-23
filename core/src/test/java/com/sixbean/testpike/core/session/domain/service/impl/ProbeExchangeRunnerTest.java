package com.sixbean.testpike.core.session.domain.service.impl;

import com.sixbean.testpike.core.events.domain.component.ProbeExchangeRunner;
import com.sixbean.testpike.core.session.domain.service.LoginStateProbeService.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared verdict/address rules of the "test now" doors (unit tier):
 * the decision table is exactly what both service doors claim - redirect
 * bounces only count as expiry when the Location reads like a login page.
 */
class ProbeExchangeRunnerTest {

    @Test
    void statusTableDecidesEveryBand() {
        assertEquals(Verdict.VALID, ProbeExchangeRunner.verdictOf(200, null));
        assertEquals(Verdict.VALID, ProbeExchangeRunner.verdictOf(204, null));
        assertEquals(Verdict.EXPIRED, ProbeExchangeRunner.verdictOf(401, null));
        assertEquals(Verdict.EXPIRED, ProbeExchangeRunner.verdictOf(403, null));
        assertEquals(Verdict.EXPIRED, ProbeExchangeRunner.verdictOf(302, "https://app.example.com/login?next=/x"));
        assertEquals(Verdict.EXPIRED, ProbeExchangeRunner.verdictOf(302, "https://sso.example.org/auth"));
        assertEquals(Verdict.UNKNOWN, ProbeExchangeRunner.verdictOf(302, "https://app.example.com/dashboard"));
        assertEquals(Verdict.UNKNOWN, ProbeExchangeRunner.verdictOf(301, null));
        assertEquals(Verdict.UNKNOWN, ProbeExchangeRunner.verdictOf(500, null));
        assertEquals(Verdict.UNKNOWN, ProbeExchangeRunner.verdictOf(404, null));
    }

    @Test
    void addressNormalizationAcceptsHttpOnly() {
        assertEquals("http://a.b/c", ProbeExchangeRunner.normalizedHttpUrl(" http://a.b/c "));
        assertEquals("https://a.b", ProbeExchangeRunner.normalizedHttpUrl("https://a.b"));
        assertNull(ProbeExchangeRunner.normalizedHttpUrl(null));
        assertNull(ProbeExchangeRunner.normalizedHttpUrl(""));
        assertNull(ProbeExchangeRunner.normalizedHttpUrl("ftp://a.b"));
        assertNull(ProbeExchangeRunner.normalizedHttpUrl("localhost:8080"));
    }

    @Test
    void verdictWordingStaysHonestPerFamily() {
        assertTrue(Verdict.VALID.human().contains("2xx"));
        assertTrue(Verdict.EXPIRED.human().contains("拒绝"));
        assertTrue(Verdict.UNKNOWN.human().contains("人工"));
        assertTrue(Verdict.FAILED.human().contains("未能"));
    }
}
