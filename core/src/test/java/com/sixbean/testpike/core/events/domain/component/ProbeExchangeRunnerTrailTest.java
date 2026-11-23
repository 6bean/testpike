package com.sixbean.testpike.core.events.domain.component;

import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Full-exchange trail rendering of the shared "test now" runner :
 * the request line, every outbound header, every response header line by line
 * and a hot-budgeted body. Secret families stay masked in the plain text and
 * ride the sealed sidecar as ciphertext only - the plaintext-never-in-the-
 * event-stream red line is asserted by a real unseal round-trip, not by shape
 * guessing.
 */
class ProbeExchangeRunnerTrailTest {

    private static final String HEX = "00112233445566778899aabbccddeeff";
    private static final String BODY_BUDGET_KEY = "session.probe.body-max-bytes";

    private final ConfigService config = mock(ConfigService.class);
    private final ExchangeSecretLedger ledger = new ExchangeSecretLedger(new ConfigCipher(HEX));

    private ProbeExchangeRunner runner(String bodyBudget) {
        when(config.getValue(eq(BODY_BUDGET_KEY), anyString())).thenReturn(bodyBudget);
        return new ProbeExchangeRunner(ledger, config);
    }

    @Test
    void requestTrailListsEveryHeaderAndMasksSecretFamilies() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.secretpayload");
        headers.put("X-Trace-Id", "trace-42");
        headers.put("Cookie", "sid=supersidvalue123; theme=dark");

        ProbeExchangeRunner.TrailPiece trail =
                runner("65536").renderRequestTrail("GET", "https://app.example.com/api/me", headers);

        assertTrue(trail.text().contains("请求行：GET https://app.example.com/api/me"), trail.text());
        assertTrue(trail.text().contains("请求头（共 3 项）"), trail.text());
        assertTrue(trail.text().contains("x-trace-id: trace-42"),
                "non-secret headers stay readable: " + trail.text());
        assertFalse(trail.text().contains("secretpayload"), "plaintext must not ride the echo");
        assertFalse(trail.text().contains("supersidvalue123"), "plaintext must not ride the echo");
        assertTrue(trail.text().contains("Bearer e…"), "masked display keeps the head fingerprint only");
        assertTrue(trail.text().contains("sid=***（总长度 16）"),
                "cookie names stay readable, values pair-masked: " + trail.text());

        assertNotNull(trail.sealed());
        List<String[]> entries = sealedEntries(trail.sealed());
        assertEquals(2, entries.size(), "authorization + cookie sealed: " + trail.sealed());
        assertEquals("request:authorization", entries.get(0)[0]);
        assertEquals("Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.secretpayload",
                ledger.unseal(entries.get(0)[1]), "sealed material round-trips for the reveal door");
        assertEquals("request:cookie", entries.get(1)[0]);
        assertEquals("sid=supersidvalue123; theme=dark", ledger.unseal(entries.get(1)[1]));
    }

    @Test
    void requestTrailWithoutSecretsSealsNothing() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");

        ProbeExchangeRunner.TrailPiece trail =
                runner("65536").renderRequestTrail("POST", "https://app.example.com/login", headers);

        assertTrue(trail.text().contains("content-type: application/json"), trail.text());
        assertTrue(trail.sealed() == null || trail.sealed().isBlank(),
                "no sealed column content without secret families: " + trail.sealed());
    }

    @Test
    void responseTrailRendersEveryHeaderLineByLineIncludingEachSetCookieValue() {
        Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
        responseHeaders.put(null, List.of(String.valueOf(HttpURLConnection.HTTP_MOVED_TEMP)));
        responseHeaders.put("set-cookie", List.of("sid=aaaa1111bbbb; Path=/", "theme=dark; Max-Age=60"));
        responseHeaders.put("content-type", List.of("application/json"));
        responseHeaders.put("x-ratelimit-limit", List.of("100"));
        ProbeExchangeRunner.ExchangeResult result = new ProbeExchangeRunner.ExchangeResult(
                302, 45, responseHeaders, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));

        ProbeExchangeRunner.TrailPiece trail = runner("65536").renderResponseTrail(result);

        assertTrue(trail.text().contains("HTTP 状态：302 · 45ms"), trail.text());
        assertTrue(trail.text().contains("x-ratelimit-limit: 100"), trail.text());
        assertTrue(trail.text().contains("content-type: application/json"), trail.text());
        assertFalse(trail.text().contains("aaaa1111bbbb"), "set-cookie values stay masked");
        assertFalse(trail.text().contains("Max-Age=60"), "set-cookie values stay masked");
        assertEquals(2, countLinesStartingWith(trail.text(), "set-cookie:"),
                "every Set-Cookie value gets its own line: " + trail.text());

        List<String[]> entries = sealedEntries(trail.sealed());
        assertEquals(2, entries.size(), trail.sealed());
        assertEquals("response:set-cookie", entries.get(0)[0]);
        assertEquals("sid=aaaa1111bbbb; Path=/", ledger.unseal(entries.get(0)[1]));
        assertEquals("theme=dark; Max-Age=60", ledger.unseal(entries.get(1)[1]));
    }

    @Test
    void bodyBudgetIsHotKnobAndOverBudgetEndsInHonestPointer() {
        String body = "0123456789ABCDEFGHIJ";
        ProbeExchangeRunner.ExchangeResult result = new ProbeExchangeRunner.ExchangeResult(
                200, 10, Map.of("content-type", List.of("text/plain")),
                body.getBytes(StandardCharsets.UTF_8));

        String trimmed = runner("8").renderResponseTrail(result).text();
        assertTrue(trimmed.contains("01234567"), trimmed);
        assertFalse(trimmed.contains("ABCDEFGHIJ"), "over-budget tail is not shown inline");
        assertTrue(trimmed.contains("共 20 字节"), "total size stated: " + trimmed);
        assertTrue(trimmed.contains("展开全文"), "honest pointer, never a silent cut: " + trimmed);

        String whole = runner(null).renderResponseTrail(result).text();
        assertTrue(whole.contains("ABCDEFGHIJ"), "factory budget keeps the body whole");
        assertFalse(whole.contains("展开全文"), "no pointer when nothing was cut");
    }

    @Test
    void binaryBodyStaysSizedAndUnexpanded() {
        ProbeExchangeRunner.ExchangeResult result = new ProbeExchangeRunner.ExchangeResult(
                200, 10, Map.of("content-type", List.of("image/png")), new byte[1000]);

        String text = runner("65536").renderResponseTrail(result).text();
        assertTrue(text.contains("二进制内容，共 1000 字节"), text);
    }

    /** sealed entries as [key, ENC:...] pairs in write order. */
    private static List<String[]> sealedEntries(String sealed) {
        List<String[]> out = new ArrayList<>();
        if (sealed == null || sealed.isBlank()) {
            return out;
        }
        for (String line : sealed.split("\n")) {
            if (!line.isBlank()) {
                String[] pair = line.split("\t", 2);
                assertEquals(2, pair.length, "each sealed line is key<TAB>token: " + line);
                out.add(pair);
            }
        }
        return out;
    }

    private static int countLinesStartingWith(String text, String prefix) {
        int count = 0;
        for (String line : text.split("\n")) {
            if (line.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }
}
